package gocore

import (
	"sync"

	"go.uber.org/zap"
)

// zapNop 返回一个丢弃全部日志的 logger。
//
// unlock-music 的若干解码器要求传入 *zap.Logger；在移动端我们把
// 日志静默掉，避免污染 Android logcat，也避免额外开销。
var (
	zapNopOnce sync.Once
	zapNopVal  *zap.Logger
)

func zapNop() *zap.Logger {
	zapNopOnce.Do(func() {
		zapNopVal = zap.NewNop()
	})
	return zapNopVal
}
