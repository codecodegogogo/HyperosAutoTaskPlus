package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import miuix.appcompat.app.AlertDialog;

/**
 * 「屏幕状态」条件那套卡片对话框的通用版：N 张可单选的卡片，点哪张选哪张，
 * 选中的那张可以展开一个数值输入框和一排单位/后缀标签。
 *
 * 样式借原生「电量」条件的资源（auto_task_select_address_item / auto_task_select_icon 等）。
 */
final class CardPicker {

    /** 一张卡片的描述 */
    static final class Spec {
        final String title;
        /** null 表示这张卡片没有输入区 */
        final String[] units;
        /** 只有一个单位时是否仍然展示输入框（单位当后缀显示） */
        int amount;
        int unit;
        int maxDigits = 5;

        Spec(String title) {
            this(title, null, 0, 0);
        }

        Spec(String title, String[] units, int amount, int unit) {
            this.title = title;
            this.units = units;
            this.amount = amount;
            this.unit = unit;
        }
    }

    interface OnPicked {
        void onPicked(int index, int amount, int unit);
    }

    private CardPicker() {
    }

    static void show(Context context, String title, List<Spec> specs, int selected, final OnPicked onPicked) {
        if (context == null || specs == null || specs.isEmpty()) {
            return;
        }
        final List<Card> cards = new ArrayList<>();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, InjectUi.dp(context, 7), 0, 0);
        for (int i = 0; i < specs.size(); i++) {
            Card card = new Card(context, specs.get(i), cards);
            cards.add(card);
            if (i > 0) {
                View gap = new View(context);
                column.addView(gap, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, InjectUi.dp(context, 11)));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = InjectUi.dp(context, 13);
            lp.rightMargin = InjectUi.dp(context, 13);
            column.addView(card.root, lp);
        }
        int sel = Math.min(Math.max(selected, 0), cards.size() - 1);
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).select(i == sel);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (int i = 0; i < cards.size(); i++) {
                            Card c = cards.get(i);
                            if (c.selected) {
                                onPicked.onPicked(i, c.amount(), c.unit);
                                return;
                            }
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static final class Card {
        final LinearLayout root;
        final ImageView check;
        final TextView title;
        final LinearLayout body;
        final EditText input;
        final TextView[] units;
        final List<Card> siblings;
        int unit;
        boolean selected;

        Card(final Context c, Spec spec, List<Card> siblings) {
            this.siblings = siblings;
            unit = spec.unit;

            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            int bg = InjectUi.appDrawable(c, "auto_task_select_address_item");
            if (bg != 0) {
                root.setBackgroundResource(bg);
            }

            FrameLayout header = new FrameLayout(c);
            check = new ImageView(c);
            int checkIcon = InjectUi.appDrawable(c, "auto_task_select_icon");
            if (checkIcon != 0) {
                check.setImageResource(checkIcon);
            }
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.CENTER_VERTICAL);
            cp.leftMargin = InjectUi.dp(c, 16);
            header.addView(check, cp);
            title = new TextView(c);
            title.setText(spec.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(title.getTypeface(), Typeface.BOLD);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.CENTER_VERTICAL);
            tp.leftMargin = InjectUi.dp(c, 45);
            tp.rightMargin = InjectUi.dp(c, 16);
            tp.topMargin = InjectUi.dp(c, 17);
            tp.bottomMargin = InjectUi.dp(c, 17);
            header.addView(title, tp);
            root.addView(header);

            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(InjectUi.dp(c, 45), 0, InjectUi.dp(c, 16), InjectUi.dp(c, 18));
            if (spec.units == null) {
                input = null;
                units = new TextView[0];
                // 没有输入区的卡片只保留一点底部留白
                body.setPadding(0, 0, 0, InjectUi.dp(c, 4));
            } else {
                input = new EditText(c);
                input.setInputType(InputType.TYPE_CLASS_NUMBER);
                input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(spec.maxDigits)});
                input.setSingleLine();
                input.setGravity(Gravity.CENTER);
                input.setHint("0");
                input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                input.setSelectAllOnFocus(true);
                input.setText(String.valueOf(spec.amount));
                body.addView(input, new LinearLayout.LayoutParams(
                        InjectUi.dp(c, 72), ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout chips = new LinearLayout(c);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                units = new TextView[spec.units.length];
                for (int i = 0; i < spec.units.length; i++) {
                    final int idx = i;
                    TextView chip = new TextView(c);
                    chip.setText(spec.units[i]);
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    chip.setPadding(InjectUi.dp(c, 10), InjectUi.dp(c, 5), InjectUi.dp(c, 10), InjectUi.dp(c, 5));
                    if (spec.units.length > 1) {
                        chip.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                unit = idx;
                                styleUnits(c);
                            }
                        });
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.leftMargin = InjectUi.dp(c, 6);
                    chips.addView(chip, lp);
                    units[i] = chip;
                }
                styleUnits(c);
                body.addView(chips, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            root.addView(body);

            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selected) {
                        return;
                    }
                    for (Card other : Card.this.siblings) {
                        other.select(other == Card.this);
                    }
                }
            });
        }

        void select(boolean sel) {
            selected = sel;
            Context c = root.getContext();
            root.setSelected(sel);
            check.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            body.setVisibility(sel ? View.VISIBLE : View.GONE);
            int normal = InjectUi.appColor(c, "task_default_task_title_color", Color.BLACK);
            int accent = InjectUi.appColor(c, "task_default_add_action_text_color", 0xFF0D84FF);
            title.setTextColor(sel ? accent : normal);
        }

        void styleUnits(Context c) {
            int accent = InjectUi.appColor(c, "task_address_select_text_color", 0xFF0D84FF);
            int muted = InjectUi.appColor(c, "task_un_select_text_color", 0x4D000000);
            boolean single = units.length == 1;
            for (int i = 0; i < units.length; i++) {
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(InjectUi.dp(c, 14));
                if (i == unit && !single) {
                    d.setColor(accent);
                    units[i].setTextColor(Color.WHITE);
                } else {
                    d.setColor(Color.TRANSPARENT);
                    d.setStroke(InjectUi.dp(c, 1), muted);
                    units[i].setTextColor(single ? InjectUi.appColor(c, "task_default_task_title_color", Color.BLACK) : muted);
                }
                units[i].setBackground(d);
            }
        }

        int amount() {
            if (input == null) {
                return 0;
            }
            try {
                String s = input.getText() == null ? "" : input.getText().toString().trim();
                return s.isEmpty() ? 0 : Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
