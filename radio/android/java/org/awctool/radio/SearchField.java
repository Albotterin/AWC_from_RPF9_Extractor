package org.awctool.radio;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** The search field above a list: a glass, the words, and a button that empties it. */
final class SearchField extends LinearLayout {
    interface Listener {
        void typed(String words);
    }

    private final Activity activity;
    private final EditText field;

    SearchField(Activity a, int hint, String words, final Listener listener) {
        super(a);
        activity = a;
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(Ui.rounded(a, Ui.SURFACE, 26));
        setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 4), 0);
        ImageView glass = new ImageView(a);
        glass.setImageResource(R.drawable.ic_search);
        glass.setColorFilter(Ui.MUTED);
        addView(glass, new LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)));
        field = new EditText(a);
        field.setHint(hint);
        field.setHintTextColor(Ui.MUTED);
        field.setTextColor(Ui.TEXT);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 8), 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setText(words);
        field.setOnEditorActionListener((v, id, e) -> {
            keyboardAway();
            return true;
        });
        addView(field, new LayoutParams(0, Ui.dp(a, 52), 1f));
        final ImageButton clear = Ui.icon(a, R.drawable.ic_close, a.getString(R.string.search_clear), 44, Ui.MUTED);
        clear.setOnClickListener(v -> field.setText(""));
        clear.setVisibility(words.isEmpty() ? INVISIBLE : VISIBLE);
        addView(clear, new LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() == 0 ? INVISIBLE : VISIBLE);
                listener.typed(s.toString());
            }
        });
    }

    /** Adds the field at the top of a screen. */
    void addTo(LinearLayout screen) {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(activity, 14), 0, Ui.dp(activity, 14), Ui.dp(activity, 6));
        screen.addView(this, lp);
    }

    void keyboardAway() {
        InputMethodManager keys = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keys != null) keys.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }
}
