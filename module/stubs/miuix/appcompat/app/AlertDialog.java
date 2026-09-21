package miuix.appcompat.app;

import android.content.Context;
import android.content.DialogInterface;

/**
 * 编译桩：miuix.appcompat.app.AlertDialog 及其 Builder 里本模块用到的几个方法。
 * 只用于 javac，运行时用安全中心自带的 miuix 实现（签名已对照 smali 核对）。
 * 真实类的父类是 androidx 的 AppCompatDialog，这里只要能通过编译即可。
 */
public class AlertDialog extends android.app.Dialog {

    protected AlertDialog(Context context) {
        super(context);
    }

    public static class Builder {

        public Builder(Context context) {
            throw new RuntimeException("stub");
        }

        public Builder setTitle(CharSequence title) {
            throw new RuntimeException("stub");
        }

        public Builder setSingleChoiceItems(CharSequence[] items, int checkedItem,
                                            DialogInterface.OnClickListener listener) {
            throw new RuntimeException("stub");
        }

        public Builder setView(android.view.View view) {
            throw new RuntimeException("stub");
        }

        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) {
            throw new RuntimeException("stub");
        }

        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) {
            throw new RuntimeException("stub");
        }

        public AlertDialog show() {
            throw new RuntimeException("stub");
        }
    }
}
