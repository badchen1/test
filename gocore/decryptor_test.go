package gocore

import (
	"bytes"
	"crypto/aes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"unlock-music.dev/cli/algo/ncm"
	common "unlock-music.dev/cli/algo/common"
)

// ---------------------------------------------------------------------------
// 测试用 NCM 构造器
//
// 依据 unlock-music ncm.go 的格式规范反向构造一个合法的 .ncm 文件，
// 用于验证解密链路能完整还原原始音频字节。
// ---------------------------------------------------------------------------

var (
	tKeyCore = []byte{
		0x68, 0x7a, 0x48, 0x52, 0x41, 0x6d, 0x73, 0x6f,
		0x35, 0x6b, 0x49, 0x6e, 0x62, 0x61, 0x78, 0x57,
	}
	tKeyMeta = []byte{
		0x23, 0x31, 0x34, 0x6c, 0x6a, 0x6b, 0x5f, 0x21,
		0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28,
	}
)

// buildTestNCM 生成一个包含指定音频负载的合法 NCM 文件。
func buildTestNCM(t *testing.T, audio []byte, fmtName string) []byte {
	t.Helper()

	var buf bytes.Buffer
	buf.WriteString("CTENFDAM")
	buf.Write([]byte{0x00, 0x00}) // 2 bytes gap

	// --- key 段 ---
	// 解码侧：bKeyRaw[i] ^= 0x64 -> AES-ECB 解密 -> 去填充 -> [17:]
	// 因此原始 keyData 前需留 17 字节前缀供切掉。
	keyCore := []byte{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00}
	plain := append(bytes.Repeat([]byte{0xAA}, 17), keyCore...)
	enc := aesECBEncryptPKCS7(t, plain, tKeyCore)
	for i := range enc {
		enc[i] ^= 0x64
	}
	var lb [4]byte
	binary.LittleEndian.PutUint32(lb[:], uint32(len(enc)))
	buf.Write(lb[:])
	buf.Write(enc)

	// --- meta 段 ---
	metaJSON, err := json.Marshal(map[string]any{
		"musicName": "test",
		"format":    fmtName,
	})
	if err != nil {
		t.Fatal(err)
	}
	metaPayload := append([]byte("music:"), metaJSON...)
	metaEnc := aesECBEncryptPKCS7(t, metaPayload, tKeyMeta)
	metaB64 := base64.StdEncoding.EncodeToString(metaEnc)

	metaDecoded := []byte("163 key(Don't modify):" + metaB64)
	for i := range metaDecoded {
		metaDecoded[i] ^= 0x63
	}
	binary.LittleEndian.PutUint32(lb[:], uint32(len(metaDecoded)))
	buf.Write(lb[:])
	buf.Write(metaDecoded)

	// --- 5 bytes gap ---
	buf.Write([]byte{0x00, 0x00, 0x00, 0x00, 0x00})

	// --- cover 段（空封面） ---
	//
	// 解码器按 coverFrameStartOffset + coverFrameLen + 4 定位音频起点，
	// 其中 coverFrameStartOffset 是 coverFrameLen 字段之后的位置。
	// 故 coverFrameLen = coverLen字段(4) + 实际封面数据(0) = 4。
	coverFrameLen := uint32(4 + 0)
	binary.LittleEndian.PutUint32(lb[:], coverFrameLen)
	buf.Write(lb[:])
	binary.LittleEndian.PutUint32(lb[:], 0) // cover 数据长度 0
	buf.Write(lb[:])
	// 解码器会再 +4 跳过 cover 数据长度字段本身，这里补齐该占位
	buf.Write([]byte{0x00, 0x00, 0x00, 0x00})

	// --- 音频段（用与解码器一致的流密码加密） ---
	encAudio := encryptNCMAudio(t, audio, keyCore)
	buf.Write(encAudio)

	return buf.Bytes()
}

// encryptNCMAudio 用 NCM 的流密码算法加密音频负载。
//
// 解码侧是 `buf[i] ^= box[(i+offset)&0xff]`，等价于异或流，
// 因此加密与解密是同一运算。
func encryptNCMAudio(t *testing.T, plain, key []byte) []byte {
	t.Helper()
	box := buildKeyBox(key)
	out := make([]byte, len(plain))
	for i := range plain {
		out[i] = plain[i] ^ box[i&0xff]
	}
	return out
}

// buildKeyBox 严格复刻 unlock-music ncm_cipher.go 的 keybox 构造。
func buildKeyBox(key []byte) []byte {
	box := make([]byte, 256)
	for i := 0; i < 256; i++ {
		box[i] = byte(i)
	}

	var j byte
	for i := 0; i < 256; i++ {
		j = box[i] + j + key[i%len(key)]
		box[i], box[j] = box[j], box[i]
	}

	ret := make([]byte, 256)
	var _i byte
	for i := 0; i < 256; i++ {
		_i = byte(i + 1)
		si := box[_i]
		sj := box[_i+si]
		ret[i] = box[si+sj]
	}
	return ret
}

func aesECBEncryptPKCS7(t *testing.T, plain, key []byte) []byte {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	pad := aes.BlockSize - len(plain)%aes.BlockSize
	padded := append(append([]byte{}, plain...), bytes.Repeat([]byte{byte(pad)}, pad)...)

	out := make([]byte, len(padded))
	for i := 0; i < len(padded); i += aes.BlockSize {
		block.Encrypt(out[i:i+aes.BlockSize], padded[i:i+aes.BlockSize])
	}
	return out
}

// ---------------------------------------------------------------------------
// 测试：NCM 全链路
// ---------------------------------------------------------------------------

func TestNCMRoundTrip(t *testing.T) {
	dir := t.TempDir()

	// 构造一段可识别的"FLAC"负载（含真实 FLAC 魔数，便于 ProbeAudioExt 识别）
	payload := append([]byte("fLaC"), bytes.Repeat([]byte{0x42}, 4096)...)

	inPath := filepath.Join(dir, "song.ncm")
	if err := os.WriteFile(inPath, buildTestNCM(t, payload, "flac"), 0o644); err != nil {
		t.Fatal(err)
	}

	// 解码器必须能 Validate 通过
	f, err := os.Open(inPath)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	dec := ncm.NewDecoder(&common.DecoderParams{
		Reader:    f,
		Extension: ".ncm",
		FilePath:  inPath,
		Logger:    zapNop(),
	})
	if err := dec.Validate(); err != nil {
		t.Fatalf("Validate 失败: %v", err)
	}

	// 走门面解密
	outPath := filepath.Join(dir, "song.flac")
	res, err := DecryptFile(inPath, outPath, nil)
	if err != nil {
		t.Fatalf("DecryptFile 返回 error: %v", err)
	}
	if res.Code != CodeOK {
		t.Fatalf("DecryptFile 失败 code=%d msg=%s", res.Code, res.Message)
	}

	got, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("解密结果与原始负载不一致: got %d bytes, want %d bytes", len(got), len(payload))
	}
}

func TestProbeAudioExt(t *testing.T) {
	dir := t.TempDir()

	cases := []struct {
		name string
		data []byte
		want string
	}{
		{"a.flac", append([]byte("fLaC"), make([]byte, 12)...), ".flac"},
		{"b.mp3", append([]byte("ID3"), make([]byte, 13)...), ".mp3"},
		{"c.ogg", append([]byte("OggS"), make([]byte, 12)...), ".ogg"},
		{"d.wav", append([]byte("RIFF"), make([]byte, 12)...), ".wav"},
	}
	for _, c := range cases {
		p := filepath.Join(dir, c.name)
		if err := os.WriteFile(p, c.data, 0o644); err != nil {
			t.Fatal(err)
		}
		if got := ProbeAudioExt(p); got != c.want {
			t.Errorf("%s: got %q want %q", c.name, got, c.want)
		}
	}
}

func TestIsSupported(t *testing.T) {
	for _, name := range []string{"a.ncm", "b.KGM", "c.qmcflac", "d.mflac", "e.kgg"} {
		if !IsSupported(name) {
			t.Errorf("%s 应为支持的格式", name)
		}
	}
	for _, name := range []string{"a.mp3", "b.txt", "c.docx"} {
		if IsSupported(name) {
			t.Errorf("%s 不应被判定为支持", name)
		}
	}
}

func TestSupportedFormatsJSON(t *testing.T) {
	s := SupportedFormats()
	if len(s) < 10 || s[0] != '[' {
		t.Fatalf("SupportedFormats 输出异常: %s", s)
	}
	var parsed []map[string]any
	if err := json.Unmarshal([]byte(s), &parsed); err != nil {
		t.Fatalf("SupportedFormats 不是合法 JSON: %v", err)
	}
	if len(parsed) != len(supportedFormats) {
		t.Fatalf("格式数量不符: %d vs %d", len(parsed), len(supportedFormats))
	}
}

func TestParseKeyFile(t *testing.T) {
	m := parseKeyFile("# comment\nabc$ekey1\n\ndef$ekey2\nbadline\n")
	if m["abc"] != "ekey1" || m["def"] != "ekey2" {
		t.Fatalf("解析结果异常: %#v", m)
	}
	if _, ok := m["badline"]; ok {
		t.Fatal("无分隔符的行不应被解析")
	}
}

func TestDecryptUnsupportedFormat(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.mp3")
	if err := os.WriteFile(p, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	res, _ := DecryptFile(p, filepath.Join(dir, "out.mp3"), nil)
	if res.Code != CodeUnsupportedFormat {
		t.Fatalf("期望 CodeUnsupportedFormat，实际 %d", res.Code)
	}
}

func TestDecryptMissingFileGivesIOError(t *testing.T) {
	dir := t.TempDir()
	res, _ := DecryptFile(filepath.Join(dir, "nope.ncm"), filepath.Join(dir, "out.flac"), nil)
	if res.Code != CodeIOError {
		t.Fatalf("期望 CodeIOError，实际 %d", res.Code)
	}
}

func TestKGGWithoutKeysReportsMissingKey(t *testing.T) {
	ClearCredentials()
	dir := t.TempDir()
	p := filepath.Join(dir, "x.kgg")
	// 写入 16 字节合法 KGG 魔数前缀，让流程走到"查密钥"这一步
	magic := []byte{0x7C, 0xD5, 0x32, 0xEB, 0x86, 0x02, 0x7F, 0x4B,
		0xA8, 0xAF, 0xA6, 0x8E, 0x0F, 0xFF, 0x99, 0x14}
	if err := os.WriteFile(p, append(magic, make([]byte, 256)...), 0o644); err != nil {
		t.Fatal(err)
	}
	res, _ := DecryptFile(p, filepath.Join(dir, "out.flac"), nil)
	if res.Code != CodeMissingKey {
		t.Fatalf("期望 CodeMissingKey，实际 code=%d msg=%s", res.Code, res.Message)
	}
}
