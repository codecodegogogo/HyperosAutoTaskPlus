package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import io.github.codecodegogogo.HyperosAutoTaskPlus.inject.SmsSendProgress.Result;

/** 短信结果回归：不连接手机、不访问短信数据库，也不发送真实短信。 */
public final class SmsSentHistoryTest {
    private static int checks;

    public static void main(String[] args) {
        invalidPartCounts();
        singlePart();
        multipartOutOfOrder();
        invalidCallbacks();
        failure();
        timeoutAndClose();
        independentMessages();
        historyClaims();
        System.out.println("短信发送记录回归测试通过：" + checks + " 项断言");
    }

    private static void invalidPartCounts() {
        for (int parts : new int[]{Integer.MIN_VALUE, -1, 0}) {
            boolean rejected = false;
            try { new SmsSendProgress(parts); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "零段或负分段数不能创建可确认的发送状态");
        }
    }

    private static void singlePart() {
        SmsSendProgress progress = new SmsSendProgress(1);
        expect(progress, 0, true, Result.SENT, "单段成功立即确认整条短信成功");
        expect(progress, 0, true, Result.IGNORED, "重复成功回调不得再次写入发送记录");
        expect(progress, 0, false, Result.IGNORED, "成功结束后的迟到失败不能改变结果");
        progress.close();
        expect(progress, 0, true, Result.IGNORED, "成功后再次关闭不会重新开放结果接收");
    }

    private static void multipartOutOfOrder() {
        // 遍历三段短信的全部回调顺序，只有最后一个不同分段成功时才能记录整条短信。
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] order : orders) {
            SmsSendProgress progress = new SmsSendProgress(3);
            expect(progress, order[0], true, Result.WAITING, "收到第一段成功仍需等待其它分段");
            expect(progress, order[0], true, Result.IGNORED, "首段重复回调不增加成功段数");
            expect(progress, order[1], true, Result.WAITING, "收到第二段成功仍不能提前记为已发送");
            expect(progress, order[1], true, Result.IGNORED, "中间分段重复回调不提前完成发送");
            expect(progress, order[2], true, Result.SENT, "全部不同分段成功才确认一次整条发送");
            for (int part : order) {
                expect(progress, part, true, Result.IGNORED, "完成后的任意重复分段均不能生成第二条记录");
                expect(progress, part, false, Result.IGNORED, "完成后的任意失败回调均忽略");
            }
        }
    }

    private static void invalidCallbacks() {
        SmsSendProgress progress = new SmsSendProgress(2);
        for (int part : new int[]{Integer.MIN_VALUE, -1, 2, 3, Integer.MAX_VALUE}) {
            expect(progress, part, true, Result.IGNORED, "越界成功回调不计入分段数");
            expect(progress, part, false, Result.IGNORED, "越界失败回调不终止有效发送");
        }
        expect(progress, 1, true, Result.WAITING, "越界回调后有效分段仍需正常累计");
        expect(progress, 0, true, Result.SENT, "越界回调不影响后续完整发送确认");
    }

    private static void failure() {
        SmsSendProgress firstFailure = new SmsSendProgress(1);
        expect(firstFailure, 0, false, Result.FAILED, "单段发送失败不能记为已发送");
        expect(firstFailure, 0, false, Result.IGNORED, "失败只处理一次");
        expect(firstFailure, 0, true, Result.IGNORED, "失败后的迟到成功不能补记已发送");

        for (int failedPart = 0; failedPart < 3; failedPart++) {
            SmsSendProgress progress = new SmsSendProgress(3);
            int successfulPart = (failedPart + 1) % 3;
            expect(progress, successfulPart, true, Result.WAITING, "部分成功不能视为整条已发送");
            expect(progress, failedPart, false, Result.FAILED, "任一未完成分段失败即终止整条确认");
            for (int part = 0; part < 3; part++) {
                expect(progress, part, true, Result.IGNORED, "部分失败后其它成功回调不能生成已发送记录");
                expect(progress, part, false, Result.IGNORED, "部分失败后其它失败回调不重复处理");
            }
            progress.close();
            expect(progress, failedPart, true, Result.IGNORED, "失败后关闭不会复活发送状态");
        }
    }

    private static void timeoutAndClose() {
        SmsSendProgress noResult = new SmsSendProgress(1);
        noResult.close();
        noResult.close();
        expect(noResult, 0, true, Result.IGNORED, "未收到结果便超时关闭时不能凭迟到成功写入记录");
        expect(noResult, 0, false, Result.IGNORED, "超时关闭后忽略迟到失败");

        SmsSendProgress partial = new SmsSendProgress(3);
        expect(partial, 2, true, Result.WAITING, "超时前的一段成功仍需等待整条结果");
        partial.close();
        for (int part = 0; part < 3; part++) {
            expect(partial, part, true, Result.IGNORED, "部分成功后超时不能再凑齐成功段数");
            expect(partial, part, false, Result.IGNORED, "部分成功后关闭不再接收失败结果");
        }
    }

    private static void independentMessages() {
        // 同一号码和正文连续发送时，每次尝试都创建独立进度；相同分段数不能互相去重。
        for (int attempt = 0; attempt < 2; attempt++) {
            SmsSendProgress repeatedMessage = new SmsSendProgress(2);
            expect(repeatedMessage, 0, true, Result.WAITING, "连续发送相同内容时重新累计本次分段");
            expect(repeatedMessage, 1, true, Result.SENT, "连续发送相同内容时两次均有各自的成功记录");
        }

        SmsSendProgress first = new SmsSendProgress(2);
        SmsSendProgress second = new SmsSendProgress(2);
        expect(first, 1, true, Result.WAITING, "第一条短信的分段单独累计");
        expect(second, 1, true, Result.WAITING, "第二条相同内容的短信不借用第一条的成功段数");
        expect(first, 0, true, Result.SENT, "第一条完成仅确认第一条记录");
        first.close();
        expect(second, 0, true, Result.SENT, "第一条关闭不妨碍第二条完成自己的记录");

        SmsSendProgress failed = new SmsSendProgress(1);
        SmsSendProgress retry = new SmsSendProgress(1);
        expect(failed, 0, false, Result.FAILED, "一次发送失败独立结束");
        expect(retry, 0, true, Result.SENT, "后续独立发送不继承上次失败状态");
    }

    private static void historyClaims() {
        SmsSentHistory.Claims claims = new SmsSentHistory.Claims();
        Object firstMessage = new Object();
        Object secondMessage = new Object();
        long now = 1_000_000L;
        for (long id : new long[]{Long.MIN_VALUE, -1L, 0L}) {
            check(!claims.claim(id, firstMessage, now), "无效短信记录 ID 不能被认领");
        }
        check(!claims.claim(41L, null, now), "缺失发送实例时不能认领短信记录");
        check(claims.claim(41L, firstMessage, now), "第一次成功发送可以认领自己的短信记录");
        check(claims.claim(41L, firstMessage, now + 1), "同一次发送重复确认沿用原记录");
        check(!claims.claim(41L, secondMessage, now + 2), "连续相同内容的另一次发送不能复用已认领记录");
        check(claims.claim(42L, secondMessage, now + 2), "另一次发送可以认领另一条系统短信记录");
        check(!claims.claim(42L, firstMessage, now + 3), "不同发送实例的记录归属彼此隔离");

        SmsSentHistory.Claims expiring = new SmsSentHistory.Claims();
        check(expiring.claim(43L, firstMessage, now), "记录建立有期限的认领关系");
        check(!expiring.claim(43L, secondMessage, now + SmsSentHistory.CLAIM_TTL_MS - 1),
                "认领期限届满前不能被另一发送实例抢占");
        check(expiring.claim(43L, secondMessage, now + SmsSentHistory.CLAIM_TTL_MS + 1),
                "过期认领会释放，避免长期占据系统短信记录 ID");
        check(!expiring.claim(43L, firstMessage, now + SmsSentHistory.CLAIM_TTL_MS + 2),
                "过期后新认领的归属立即生效");
    }

    private static void expect(SmsSendProgress progress, int part, boolean success, Result expected, String message) {
        Result actual = progress.accept(part, success);
        check(actual == expected, message + "，预期 " + expected + "，实际 " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
