package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.miui.autotask.taskitem.TaskItem;
import java.util.Locale;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

/** The native end-of-period alarm is the event source, not TaskItem.m(). */
public class TimePeriodExitConditionItem extends TaskItem {
    private static final long serialVersionUID = 1L;
    private int startMinute;
    private int endMinute;

    @Override public String e() { return FirstAppKeys.KEY_TIME_PERIOD_EXIT; }
    @Override public String h() { return "时间段结束时"; }
    @Override public String g() {
        return (startMinute > endMinute ? "次日 " : "")
                + String.format(Locale.getDefault(), "%02d:%02d", endMinute / 60, endMinute % 60);
    }
    @Override public int b() { return icon(1); }
    @Override public int c() { return icon(0); }
    @Override public int i() { return icon(2); }
    @Override public boolean l() {
        return startMinute >= 0 && startMinute < 1440 && endMinute >= 0 && endMinute < 1440;
    }
    @Override public boolean m() { return false; }

    public boolean setWindow(int start, int end) {
        boolean changed = startMinute != start || endMinute != end;
        startMinute = start;
        endMinute = end;
        return changed;
    }

    public boolean matches(int start, int end) {
        return l() && startMinute == start && endMinute == end;
    }

    private static int icon(int which) {
        return InjectUi.icons("auto_task_icon_custom_time", android.R.drawable.ic_menu_recent_history)[which];
    }
}
