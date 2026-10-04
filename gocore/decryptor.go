// Package gocore 是 Kugo Music Converter 的 Android 移植核心。
//
// 本包通过 gomobile bind 暴露一个极简门面给 Kotlin 调用，刻意避开
// 一切 gomobile 不支持的类型（interface{}、map、可变参数等）。
package gocore

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"kugomobile/gocore/internal/algo/kgg"
	"kugomobile/gocore/internal/algo/qmcfile"

	common "unlock-music.dev/cli/algo/common"
	"unlock-music.dev/cli/algo/kgm"
	"unlock-music.dev/cli/algo/kwm"
	"unlock-music.dev/cli/algo/ncm"
	"unlock-music.dev/cli/algo/qmc"
)

// ---------------------------------------------------------------------------
// 错误码：gomobile 无法跨语言传递 error，统一用 int32 返回给 Kotlin。
//
// ⚠️ 这些码值被 mobile 侧的 DecryptEngine.kt 镜像使用，顺序不可随意调整。
// 如需新增，请追加到末尾，并同步更新 Kotlin 侧常量。
// ---------------------------------------------------------------------------

const (
	CodeOK                int32 = iota // 0 成功
	CodeUnsupportedFormat              // 1 格式不受支持
	CodeMissingKey                     // 2 缺少密钥
	CodeDecryptFailed                  // 3 解密过程失败
	CodeIOError                        // 4 文件读写错误
	CodeCancelled                      // 5 已取消
)

// FormatInfo 描述一种受支持的加密格式。
type FormatInfo struct {
	Ext         string // 扩展名，含点，如 ".ncm"
	DisplayName string // 展示名
	Platform    string // 所属平台
	NeedExtraKey bool   // 是否需要额外密钥（KGG / MFLAC 等）
}

// Progress 解密进度回调。done/total 单位是字节。
//
// gomobile 只支持单一接口方法作为回调，因此这里保持极简签名，
// Kotlin 侧用 lambda 实现即可。
type Progress interface {
	OnProgress(done int64, total int64)
}

// ---------------------------------------------------------------------------
// 支持的格式表
// ---------------------------------------------------------------------------

var supportedFormats = []FormatInfo{
	{".kgg", "酷狗 KGG", "酷狗音乐", true},
	{".kgm", "酷狗 KGM", "酷狗音乐", false},
	{".kgma", "酷狗 KGMA", "酷狗音乐", false},
	{".vpr", "酷狗 VPR", "酷狗音乐", false},
	{".ncm", "网易云 NCM", "网易云音乐", false},
	{".kwm", "酷我 KWM", "酷我音乐", false},
	{".qmc0", "QQ音乐 QMC0", "QQ音乐", false},
	{".qmc2", "QQ音乐 QMC2", "QQ音乐", false},
	{".qmc3", "QQ音乐 QMC3", "QQ音乐", false},
	{".qmc4", "QQ音乐 QMC4", "QQ音乐", false},
	{".qmc6", "QQ音乐 QMC6", "QQ音乐", false},
	{".qmc8", "QQ音乐 QMC8", "QQ音乐", false},
	{".qmcflac", "QQ音乐 QMCFLAC", "QQ音乐", false},
	{".qmcogg", "QQ音乐 QMCOGG", "QQ音乐", false},
	{".tkm", "QQ音乐 TKM", "QQ音乐", false},
	{".mflac", "QQ音乐 MFLAC", "QQ音乐", true},
	{".mgg", "QQ音乐 MGG", "QQ音乐", true},
}

// SupportedFormats 返回支持格式的 JSON 数组。
//
// gomobile 不支持结构体切片返回值，所以这里手工拼 JSON 字符串。
func SupportedFormats() string {
	var b strings.Builder
	b.WriteByte('[')
	for i, f := range supportedFormats {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b,
			`{"ext":%q,"displayName":%q,"platform":%q,"needExtraKey":%t}`,
			f.Ext, f.DisplayName, f.Platform, f.NeedExtraKey)
	}
	b.WriteByte(']')
	return b.String()
}

// SupportedExts 返回逗号分隔的扩展名列表，便于 Kotlin 侧做后缀校验。
func SupportedExts() string {
	exts := make([]string, 0, len(supportedFormats))
	for _, f := range supportedFormats {
		exts = append(exts, f.Ext)
	}
	return strings.Join(exts, ",")
}

// IsSupported 判断给定文件名/扩展名是否受支持。
func IsSupported(nameOrExt string) bool {
	ext := strings.ToLower(nameOrExt)
	if !strings.HasPrefix(ext, ".") {
		ext = strings.ToLower(filepath.Ext(nameOrExt))
	}
	for _, f := range supportedFormats {
		if f.Ext == ext {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------------------
// 全局凭据：KGG 的密钥库、QQ音乐的 ekey
// ---------------------------------------------------------------------------

var (
	credMu       sync.RWMutex
	kggKeyMap    map[string]string // audioHash -> ekey
	qmcEKeyMap   map[string]string // mediaMid / 文件名 -> ekey
	qmcCookieRaw string
)

// SetKGGKeyFile 载入酷狗 kgg.key（格式: <hash>$<ekey>，每行一条）。
func SetKGGKeyFile(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	parsed := parseKeyFile(string(data))
	credMu.Lock()
	kggKeyMap = parsed
	credMu.Unlock()
	return nil
}

// SetKGGDatabaseFile 载入酷狗 KGMusicV3.db 并解密出密钥表。
func SetKGGDatabaseFile(path string) error {
	m, err := kgg.LoadKGDatabaseKeyMap(path)
	if err != nil {
		return err
	}
	credMu.Lock()
	kggKeyMap = m
	credMu.Unlock()
	return nil
}

// SetKGGKeys 直接注入 "hash$ekey" 多行文本，供用户粘贴。
func SetKGGKeys(text string) {
	parsed := parseKeyFile(text)
	credMu.Lock()
	if kggKeyMap == nil {
		kggKeyMap = map[string]string{}
	}
	for k, v := range parsed {
		kggKeyMap[k] = v
	}
	credMu.Unlock()
}

// SetQMCKeys 注入 QQ音乐的 ekey 映射，文本格式每行 "<key>$<ekey>"。
//
// key 可以是 media_mid，也可以是文件名（不含扩展名），后者按文件名兜底匹配。
func SetQMCKeys(text string) {
	parsed := parseKeyFile(text)
	credMu.Lock()
	if qmcEKeyMap == nil {
		qmcEKeyMap = map[string]string{}
	}
	for k, v := range parsed {
		qmcEKeyMap[k] = v
	}
	credMu.Unlock()
}

// SetQMCCookie 保存 QQ音乐 Cookie，供未来自动换取 ekey 使用。
func SetQMCCookie(cookie string) {
	credMu.Lock()
	qmcCookieRaw = cookie
	credMu.Unlock()
}

// ClearCredentials 清空全部已导入凭据。
func ClearCredentials() {
	credMu.Lock()
	kggKeyMap = nil
	qmcEKeyMap = nil
	qmcCookieRaw = ""
	credMu.Unlock()
}

// HasKGGKeys 判断是否已导入酷狗密钥。
func HasKGGKeys() bool {
	credMu.RLock()
	defer credMu.RUnlock()
	return len(kggKeyMap) > 0
}

// HasQMCKeys 判断是否已导入 QQ音乐 ekey。
func HasQMCKeys() bool {
	credMu.RLock()
	defer credMu.RUnlock()
	return len(qmcEKeyMap) > 0
}

func parseKeyFile(text string) map[string]string {
	out := map[string]string{}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimRight(line, "\r")
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		idx := strings.Index(line, "$")
		if idx < 0 {
			continue
		}
		k := strings.TrimSpace(line[:idx])
		v := strings.TrimSpace(line[idx+1:])
		if k == "" {
			continue
		}
		out[k] = v
	}
	return out
}

// ---------------------------------------------------------------------------
// 探针：读文件头判断真实音频格式
// ---------------------------------------------------------------------------

// ProbeAudioExt 通过文件头魔数判断真实音频扩展名（如 ".flac" / ".mp3"）。
// 返回空串表示无法识别。
func ProbeAudioExt(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()

	head := make([]byte, 16)
	n, _ := io.ReadFull(f, head)
	if n < 4 {
		return ""
	}
	head = head[:n]

	switch {
	case n >= 4 && string(head[:4]) == "fLaC":
		return ".flac"
	case n >= 3 && string(head[:3]) == "ID3":
		return ".mp3"
	case n >= 2 && head[0] == 0xFF && head[1]&0xE0 == 0xE0:
		return ".mp3"
	case n >= 4 && string(head[:4]) == "RIFF":
		return ".wav"
	case n >= 4 && string(head[:4]) == "OggS":
		return ".ogg"
	case n >= 4 && string(head[:4]) == "M4A ":
		return ".m4a"
	case n >= 4 && string(head[:4]) == "ftyp":
		return ".m4a"
	case n >= 4 && string(head[:4]) == "ADIF":
		return ".aac"
	default:
		return ""
	}
}

// ---------------------------------------------------------------------------
// 核心：解密
// ---------------------------------------------------------------------------

// DecryptResult 承载一次解密的结果。
//
// gomobile 只允许函数返回 (T, error) 两个值，因此这里用一个结构体
// 把「输出路径 / 错误码 / 可读消息」打包返回。
type DecryptResult struct {
	OutPath string // 成功时的产物绝对路径
	Code    int32  // 见 CodeOK 等常量
	Message string // 失败原因，可直接展示给用户
}

// DecryptFile 把一个加密音频解密到 outPath。
//
// 成功时返回的 DecryptResult.Code 为 CodeOK；失败时 Code 为具体错误码，
// Message 是可直接展示的中文原因。第二个返回值恒为 nil，
// 仅用于满足 gomobile 的签名要求。
func DecryptFile(inPath string, outPath string, progress Progress) (*DecryptResult, error) {
	res := &DecryptResult{}

	ext := strings.ToLower(filepath.Ext(inPath))
	src, err := openDecryptReader(inPath, ext)
	if err != nil {
		res.Code = classify(err)
		res.Message = err.Error()
		return res, nil
	}
	defer src.Close()

	total := int64(-1)
	if st, statErr := os.Stat(inPath); statErr == nil {
		total = st.Size()
	}

	dst, err := os.Create(outPath)
	if err != nil {
		res.Code = CodeIOError
		res.Message = err.Error()
		return res, nil
	}

	written, copyErr := copyWithProgress(dst, src, total, progress)
	closeErr := dst.Close()
	if copyErr != nil {
		_ = os.Remove(outPath)
		res.Code = classify(copyErr)
		res.Message = copyErr.Error()
		return res, nil
	}
	if closeErr != nil {
		_ = os.Remove(outPath)
		res.Code = CodeIOError
		res.Message = closeErr.Error()
		return res, nil
	}
	// 解密结果为空视为失败，避免产出 0 字节垃圾文件。
	if written == 0 {
		_ = os.Remove(outPath)
		res.Code = CodeDecryptFailed
		res.Message = "decrypted output is empty"
		return res, nil
	}

	res.OutPath = outPath
	res.Code = CodeOK
	return res, nil
}

// DecryptToSameDir 是 DecryptFile 的便捷封装：输出到源文件同目录，
// 文件名去掉加密后缀并自动探测真实扩展名，重名时追加 _1 / _2。
func DecryptToSameDir(inPath string, progress Progress) (*DecryptResult, error) {
	outPath, err := buildOutputPath(inPath)
	if err != nil {
		return &DecryptResult{Code: CodeIOError, Message: err.Error()}, nil
	}
	return DecryptFile(inPath, outPath, progress)
}

// DecryptToDir 把结果输出到指定目录，文件名规则同 DecryptToSameDir。
// outDir 为空时退化为输出到源文件同目录。
func DecryptToDir(inPath string, outDir string, progress Progress) (*DecryptResult, error) {
	if outDir == "" {
		return DecryptToSameDir(inPath, progress)
	}
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return &DecryptResult{Code: CodeIOError, Message: err.Error()}, nil
	}
	base := outputBaseName(inPath)
	candidate := filepath.Join(outDir, base)
	outPath, err := uniquePath(candidate)
	if err != nil {
		return &DecryptResult{Code: CodeIOError, Message: err.Error()}, nil
	}
	return DecryptFile(inPath, outPath, progress)
}

func buildOutputPath(inPath string) (string, error) {
	dir := filepath.Dir(inPath)
	candidate := filepath.Join(dir, outputBaseName(inPath))
	return uniquePath(candidate)
}

// outputBaseName 生成去除加密后缀的输出文件名。
//
// 例如 /x/歌曲.ncm -> 歌曲.flac（若已知）；扩展名未知时默认先给 .flac，
// 调用方可再依据 ProbeAudioExt 修正。
func outputBaseName(inPath string) string {
	base := filepath.Base(inPath)
	ext := strings.ToLower(filepath.Ext(base))
	name := strings.TrimSuffix(base, filepath.Ext(base))

	// 先尝试从源文件头部直接嗅探真实格式（部分格式尾部才是密文，
	// 对 NCM 这类头部即加密的不适用，所以仅作为弱提示）。
	guess := probeByContainer(inPath, ext)
	if guess == "" {
		guess = ".flac"
	}
	return name + guess
}

// probeByContainer 依据来源格式推断合理的默认输出扩展名。
func probeByContainer(inPath, ext string) string {
	switch ext {
	case ".ncm":
		// NCM 内部元数据记录了真实格式，交由 decoder 决定；
		// 这里给通用兜底，Kotlin 侧可用 ProbeAudioExt 复核。
		return ".mp3"
	case ".kwm":
		return ".mp3"
	case ".qmcogg":
		return ".ogg"
	case ".qmcflac", ".qmc0", ".qmc2", ".qmc3", ".qmc4", ".qmc6", ".qmc8":
		return ".flac"
	case ".mflac":
		return ".flac"
	case ".mgg":
		return ".ogg"
	case ".kgg", ".kgm", ".kgma", ".vpr":
		return ".flac"
	default:
		return ""
	}
}

func uniquePath(p string) (string, error) {
	if _, err := os.Stat(p); errors.Is(err, os.ErrNotExist) {
		return p, nil
	}
	ext := filepath.Ext(p)
	stem := strings.TrimSuffix(p, ext)
	for i := 1; i < 10000; i++ {
		cand := fmt.Sprintf("%s_%d%s", stem, i, ext)
		if _, err := os.Stat(cand); errors.Is(err, os.ErrNotExist) {
			return cand, nil
		}
	}
	return "", fmt.Errorf("无法为 %s 找到可用的输出文件名", p)
}

func copyWithProgress(dst io.Writer, src io.Reader, total int64, progress Progress) (int64, error) {
	buf := make([]byte, 256*1024)
	var written int64
	var lastReport time.Time

	for {
		n, readErr := src.Read(buf)
		if n > 0 {
			if _, wErr := dst.Write(buf[:n]); wErr != nil {
				return written, wErr
			}
			written += int64(n)
			// 限流：最多每 100ms 回调一次，避免跨语言调用过于频繁。
			if progress != nil && time.Since(lastReport) > 100*time.Millisecond {
				progress.OnProgress(written, total)
				lastReport = time.Now()
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return written, readErr
		}
	}
	if progress != nil {
		progress.OnProgress(written, total)
	}
	return written, nil
}

// ---------------------------------------------------------------------------
// 各格式解密读取器构造
// ---------------------------------------------------------------------------

func openDecryptReader(inPath, ext string) (io.ReadCloser, error) {
	switch ext {
	case ".kgm", ".kgma", ".vpr":
		return openKGM(inPath)
	case ".kgg":
		return openKGG(inPath)
	case ".ncm":
		return openNCM(inPath)
	case ".kwm":
		return openKWM(inPath)
	case ".qmc0", ".qmc2", ".qmc3", ".qmc4", ".qmc6", ".qmc8",
		".qmcflac", ".qmcogg", ".tkm":
		return openQMC(inPath, ext)
	case ".mflac", ".mgg":
		return openQMCModern(inPath)
	default:
		return nil, errUnsupportedFormat
	}
}

// errUnsupportedFormat 等内部哨兵错误，由 classify 映射到错误码。
var (
	errUnsupportedFormat = errors.New("unsupported input format")
	errMissingKey        = errors.New("decryption key missing")
	errDecryptFailed     = errors.New("decrypt process failed")
	errIOFailure         = errors.New("io error")
)

func classify(err error) int32 {
	switch {
	case err == nil:
		return CodeOK
	case errors.Is(err, errUnsupportedFormat),
		errors.Is(err, kgg.ErrUnsupportedMode),
		errors.Is(err, kgg.ErrInvalidKGGHeader),
		errors.Is(err, qmcfile.ErrUnsupportedFooter):
		return CodeUnsupportedFormat
	case errors.Is(err, errMissingKey),
		errors.Is(err, kgg.ErrKeyNotFound),
		errors.Is(err, qmcfile.ErrMissingEKey),
		errors.Is(err, qmcfile.ErrInvalidEKey):
		return CodeMissingKey
	default:
		if os.IsNotExist(err) || os.IsPermission(err) {
			return CodeIOError
		}
		return CodeDecryptFailed
	}
}

// --- 各格式实现 ---

type stackedReadCloser struct {
	reader  io.Reader
	closers []io.Closer
	closed  bool
}

func (s *stackedReadCloser) Read(p []byte) (int, error) { return s.reader.Read(p) }

func (s *stackedReadCloser) Close() error {
	if s.closed {
		return nil
	}
	s.closed = true
	var firstErr error
	for i := len(s.closers) - 1; i >= 0; i-- {
		if s.closers[i] == nil {
			continue
		}
		if err := s.closers[i].Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

// panicSafeReadCloser 把第三方 decoder 的 panic 转为 error。
// unlock-music 的若干解码器在遇到畸形输入时会 panic，在移动端必须兜住。
type panicSafeReadCloser struct {
	inner io.ReadCloser
}

func (p *panicSafeReadCloser) Read(buf []byte) (n int, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("%w: decoder panic: %v", errDecryptFailed, r)
		}
	}()
	return p.inner.Read(buf)
}

func (p *panicSafeReadCloser) Close() (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("%w: decoder panic: %v", errDecryptFailed, r)
		}
	}()
	return p.inner.Close()
}

func guard(inner io.ReadCloser) io.ReadCloser { return &panicSafeReadCloser{inner: inner} }

func openKGM(inPath string) (io.ReadCloser, error) {
	in, err := os.Open(inPath)
	if err != nil {
		return nil, err
	}
	dec := kgm.NewDecoder(&common.DecoderParams{Reader: in})
	if err := dec.Validate(); err != nil {
		_ = in.Close()
		return nil, fmt.Errorf("%w: invalid KGM/KGMA/VPR: %v", errDecryptFailed, err)
	}
	return guard(&stackedReadCloser{reader: dec, closers: []io.Closer{in}}), nil
}

func openNCM(inPath string) (io.ReadCloser, error) {
	in, err := os.Open(inPath)
	if err != nil {
		return nil, err
	}
	dec := ncm.NewDecoder(&common.DecoderParams{
		Reader:    in,
		Extension: strings.ToLower(filepath.Ext(inPath)),
		FilePath:  inPath,
		Logger:    zapNop(),
	})
	if err := dec.Validate(); err != nil {
		_ = in.Close()
		return nil, fmt.Errorf("%w: invalid NCM: %v", errDecryptFailed, err)
	}
	return guard(&stackedReadCloser{reader: dec, closers: []io.Closer{in}}), nil
}

func openKWM(inPath string) (io.ReadCloser, error) {
	in, err := os.Open(inPath)
	if err != nil {
		return nil, err
	}
	dec := kwm.NewDecoder(&common.DecoderParams{
		Reader:    in,
		Extension: ".kwm",
		FilePath:  inPath,
		Logger:    zapNop(),
	})
	if err := dec.Validate(); err != nil {
		_ = in.Close()
		return nil, fmt.Errorf("%w: invalid KWM: %v", errDecryptFailed, err)
	}
	return guard(&stackedReadCloser{reader: dec, closers: []io.Closer{in}}), nil
}

func openQMC(inPath, ext string) (io.ReadCloser, error) {
	in, err := os.Open(inPath)
	if err != nil {
		return nil, err
	}
	dec := qmc.NewDecoder(&common.DecoderParams{
		Reader:    in,
		Extension: ext,
		FilePath:  inPath,
		Logger:    zapNop(),
	})
	if err := dec.Validate(); err != nil {
		_ = in.Close()
		return nil, fmt.Errorf("%w: invalid QMC: %v", errDecryptFailed, err)
	}
	return guard(&stackedReadCloser{reader: dec, closers: []io.Closer{in}}), nil
}

// openKGG 使用已导入的酷狗密钥表解密。
func openKGG(inPath string) (io.ReadCloser, error) {
	credMu.RLock()
	keys := kggKeyMap
	credMu.RUnlock()
	if len(keys) == 0 {
		return nil, fmt.Errorf("%w: 请先导入酷狗密钥（kgg.key 或 KGMusicV3.db）", errMissingKey)
	}

	f, err := os.Open(inPath)
	if err != nil {
		return nil, err
	}
	dec, err := kgg.NewDecoder(
		&kgg.DecoderParams{Reader: f, Path: inPath},
		kgg.MemoryKeyProvider{Cache: keys},
	)
	if err != nil {
		_ = f.Close()
		if errors.Is(err, kgg.ErrKeyNotFound) {
			return nil, fmt.Errorf("%w: %v", errMissingKey, err)
		}
		if errors.Is(err, kgg.ErrUnsupportedMode) || errors.Is(err, kgg.ErrInvalidKGGHeader) {
			return nil, fmt.Errorf("%w: %v", errUnsupportedFormat, err)
		}
		return nil, fmt.Errorf("%w: %v", errDecryptFailed, err)
	}
	return guard(dec), nil
}

// openQMCModern 处理 MFLAC / MGG，需要用户导入 ekey。
func openQMCModern(inPath string) (io.ReadCloser, error) {
	info, err := qmcfile.Inspect(inPath)
	if err != nil {
		return nil, fmt.Errorf("%w: invalid QMC container: %v", errDecryptFailed, err)
	}

	switch info.Kind {
	case qmcfile.KindLegacy:
		return openQMC(inPath, strings.ToLower(filepath.Ext(inPath)))
	case qmcfile.KindQTag, qmcfile.KindMusicEx:
		// QTag 与 musicex 都需要 ekey。
		return openQMCWithEKey(inPath, info)
	case qmcfile.KindSTag:
		return nil, fmt.Errorf("%w: STag 容器暂不支持", errUnsupportedFormat)
	default:
		return nil, fmt.Errorf("%w: 未知的 QMC 容器", errUnsupportedFormat)
	}
}

func openQMCWithEKey(inPath string, info qmcfile.Info) (io.ReadCloser, error) {
	ekey := lookupQMCKey(inPath, info)
	if ekey == "" {
		return nil, fmt.Errorf("%w: 请先导入该文件的 QQ音乐 ekey", errMissingKey)
	}
	reader, _, err := qmcfile.Open(inPath, ekey)
	if err != nil {
		switch {
		case errors.Is(err, qmcfile.ErrMissingEKey):
			return nil, fmt.Errorf("%w: %v", errMissingKey, err)
		case errors.Is(err, qmcfile.ErrInvalidEKey):
			return nil, fmt.Errorf("%w: ekey 无效或已过期", errMissingKey)
		case errors.Is(err, qmcfile.ErrUnsupportedFooter):
			return nil, fmt.Errorf("%w: %v", errUnsupportedFormat, err)
		default:
			return nil, fmt.Errorf("%w: %v", errDecryptFailed, err)
		}
	}
	return guard(reader), nil
}

// lookupQMCKey 依次用 media_mid、文件名（含/不含扩展名）匹配已导入的 ekey。
func lookupQMCKey(inPath string, info qmcfile.Info) string {
	credMu.RLock()
	defer credMu.RUnlock()
	if len(qmcEKeyMap) == 0 {
		return ""
	}

	base := filepath.Base(inPath)
	stem := strings.TrimSuffix(base, filepath.Ext(base))

	candidates := []string{}
	if info.Metadata != nil && info.Metadata.MediaMID != "" {
		candidates = append(candidates, info.Metadata.MediaMID)
	}
	candidates = append(candidates, stem, base)

	for _, c := range candidates {
		if v, ok := qmcEKeyMap[c]; ok && v != "" {
			return v
		}
	}
	return ""
}
