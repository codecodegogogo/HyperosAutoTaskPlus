package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

/** 一次逻辑短信的分段结果，只在所有分段成功后确认一次，失败或超时后不再补记成功。 */
final class SmsSendProgress {
    enum Result { WAITING, SENT, FAILED, IGNORED }

    private final boolean[] succeeded;
    private int count;
    private boolean closed;

    SmsSendProgress(int parts) {
        if (parts <= 0) throw new IllegalArgumentException("短信分段数必须大于零");
        succeeded = new boolean[parts];
    }

    Result accept(int part, boolean success) {
        if (closed || part < 0 || part >= succeeded.length || succeeded[part]) return Result.IGNORED;
        if (!success) {
            closed = true;
            return Result.FAILED;
        }
        succeeded[part] = true;
        if (++count != succeeded.length) return Result.WAITING;
        closed = true;
        return Result.SENT;
    }

    void close() { closed = true; }
}
