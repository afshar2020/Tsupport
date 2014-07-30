/*
 * This is the source code of Telegram for Android v. 1.4.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2014.
 */

package org.tdesktop.ui.Views;

import android.animation.Animator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.tdesktop.android.AndroidUtilities;
import org.tdesktop.android.Emoji;
import org.tdesktop.android.LocaleController;
import org.tdesktop.android.MediaController;
import org.tdesktop.android.MessagesController;
import org.tdesktop.android.TemplateSupport;
import org.tdesktop.messenger.ConnectionsManager;
import org.tdesktop.messenger.FileLog;
import org.tdesktop.messenger.NotificationCenter;
import org.tdesktop.messenger.R;
import org.tdesktop.messenger.TLRPC;
import org.tdesktop.objects.MessageObject;
import org.tdesktop.ui.ApplicationLoader;

import java.util.ArrayList;

public class ChatActivityEnterView implements NotificationCenter.NotificationCenterDelegate, SizeNotifierRelativeLayout.SizeNotifierRelativeLayoutDelegate {

    public static interface ChatActivityEnterViewDelegate {
        public abstract void onMessageSend();
        public abstract void needSendTyping();
    }

    private EditText messsageEditText = null;
    private ImageButton sendButton;
    private PopupWindow emojiPopup;
    private ImageView emojiButton;
    private EmojiView emojiView;
    private TextView recordTimeText;
    private ImageButton audioSendButton;
    private View recordPanel;
    private View slideText;
    private PowerManager.WakeLock mWakeLock = null;
    private SizeNotifierRelativeLayout sizeNotifierRelativeLayout;
    private ArrayList<MessageObject> messages = new ArrayList<MessageObject>();
    private int minMessageId = Integer.MIN_VALUE;
    private int maxDate = Integer.MIN_VALUE;

    private int keyboardHeight = 0;
    private int keyboardHeightLand = 0;
    private boolean keyboardVisible;
    private boolean sendByEnter = false;
    private long lastTypingTimeSend = 0;
    private String lastTimeString = null;
    private float startedDraggingX = -1;
    private float distCanMove = AndroidUtilities.dp(80);
    private boolean recordingAudio = false;

    private Activity parentActivity;
    private long dialog_id;
    private boolean ignoreTextChange = false;
    private ChatActivityEnterViewDelegate delegate;
    private TextWatcher textWatcher = null;

    public ChatActivityEnterView() {
        NotificationCenter.getInstance().addObserver(this, MediaController.recordStarted);
        NotificationCenter.getInstance().addObserver(this, MediaController.recordStartError);
        NotificationCenter.getInstance().addObserver(this, MediaController.recordStopped);
        NotificationCenter.getInstance().addObserver(this, MediaController.recordProgressChanged);
        NotificationCenter.getInstance().addObserver(this, MessagesController.closeChats);
        NotificationCenter.getInstance().addObserver(this, MediaController.audioDidSent);
        NotificationCenter.getInstance().addObserver(this, 999);
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        sendByEnter = preferences.getBoolean("send_by_enter", false);
    }

    public void onDestroy() {
        NotificationCenter.getInstance().removeObserver(this, MediaController.recordStarted);
        NotificationCenter.getInstance().removeObserver(this, MediaController.recordStartError);
        NotificationCenter.getInstance().removeObserver(this, MediaController.recordStopped);
        NotificationCenter.getInstance().removeObserver(this, MediaController.recordProgressChanged);
        NotificationCenter.getInstance().removeObserver(this, MessagesController.closeChats);
        NotificationCenter.getInstance().removeObserver(this, MediaController.audioDidSent);
        NotificationCenter.getInstance().removeObserver(this, 999);
        if (mWakeLock != null) {
            try {
                mWakeLock.release();
                mWakeLock = null;
            } catch (Exception e) {
                FileLog.e("tdesktop", e);
            }
        }
        if (sizeNotifierRelativeLayout != null) {
            sizeNotifierRelativeLayout.delegate = null;
            sizeNotifierRelativeLayout = null;
        }
    }

    public void setContainerView(Activity activity, View containerView) {
        parentActivity = activity;

        sizeNotifierRelativeLayout = (SizeNotifierRelativeLayout)containerView.findViewById(R.id.chat_layout);
        sizeNotifierRelativeLayout.delegate = this;

        messsageEditText = (EditText)containerView.findViewById(R.id.chat_text_edit);
        messsageEditText.setHint(LocaleController.getString("TypeMessage", R.string.TypeMessage));
        sendButton = (ImageButton)containerView.findViewById(R.id.chat_send_button);
        sendButton.setEnabled(true);
        emojiButton = (ImageView)containerView.findViewById(R.id.chat_smile_button);
        audioSendButton = (ImageButton)containerView.findViewById(R.id.chat_audio_send_button);
        audioSendButton.setEnabled(false);
        audioSendButton.setVisibility(View.INVISIBLE);
        recordPanel = containerView.findViewById(R.id.record_panel);
        recordTimeText = (TextView)containerView.findViewById(R.id.recording_time_text);
        slideText = containerView.findViewById(R.id.slideText);
        TextView textView = (TextView)containerView.findViewById(R.id.slideToCancelTextView);
        textView.setText(LocaleController.getString("SlideToCancel", R.string.SlideToCancel));

        emojiButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (emojiPopup == null) {
                    showEmojiPopup(true);
                } else {
                    showEmojiPopup(!emojiPopup.isShowing());
                }
            }
        });

        messsageEditText.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int i, KeyEvent keyEvent) {
                if (i == 4 && !keyboardVisible && emojiPopup != null && emojiPopup.isShowing()) {
                    if (keyEvent.getAction() == 1) {
                        showEmojiPopup(false);
                    }
                    return true;
                } else if (i == KeyEvent.KEYCODE_ENTER && sendByEnter && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        messsageEditText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (emojiPopup != null && emojiPopup.isShowing()) {
                    showEmojiPopup(false);
                }
            }
        });

        messsageEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
                if (i == EditorInfo.IME_ACTION_SEND) {
                    sendMessage();
                    return true;
                } else if (sendByEnter) {
                    if (keyEvent != null && i == EditorInfo.IME_NULL && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                        sendMessage();
                        return true;
                    }
                }
                return false;
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendMessage();
            }
        });

        audioSendButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    startedDraggingX = -1;
                    MediaController.getInstance().startRecording(dialog_id);
                    updateAudioRecordIntefrace();
                    audioSendButton.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (motionEvent.getAction() == MotionEvent.ACTION_UP || motionEvent.getAction() == MotionEvent.ACTION_CANCEL) {
                    startedDraggingX = -1;
                    MediaController.getInstance().stopRecording(true);
                    recordingAudio = false;
                    updateAudioRecordIntefrace();
                } else if (motionEvent.getAction() == MotionEvent.ACTION_MOVE && recordingAudio) {
                    float x = motionEvent.getX();
                    if (x < -distCanMove) {
                        MediaController.getInstance().stopRecording(false);
                        recordingAudio = false;
                        updateAudioRecordIntefrace();
                    }
                    if(android.os.Build.VERSION.SDK_INT > 13) {
                        x = x + audioSendButton.getX();
                        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)slideText.getLayoutParams();
                        if (startedDraggingX != -1) {
                            float dist = (x - startedDraggingX);
                            params.leftMargin = AndroidUtilities.dp(30) + (int)dist;
                            slideText.setLayoutParams(params);
                            float alpha = 1.0f + dist / distCanMove;
                            if (alpha > 1) {
                                alpha = 1;
                            } else if (alpha < 0) {
                                alpha = 0;
                            }
                            slideText.setAlpha(alpha);
                        }
                        if (x <= slideText.getX() + slideText.getWidth() + AndroidUtilities.dp(30)) {
                            if (startedDraggingX == -1) {
                                startedDraggingX = x;
                                distCanMove = (recordPanel.getMeasuredWidth() - slideText.getMeasuredWidth() - AndroidUtilities.dp(48)) / 2.0f;
                                if (distCanMove <= 0) {
                                    distCanMove = AndroidUtilities.dp(80);
                                } else if (distCanMove > AndroidUtilities.dp(80)) {
                                    distCanMove = AndroidUtilities.dp(80);
                                }
                            }
                        }
                        if (params.leftMargin > AndroidUtilities.dp(30)) {
                            params.leftMargin = AndroidUtilities.dp(30);
                            slideText.setLayoutParams(params);
                            slideText.setAlpha(1);
                            startedDraggingX = -1;
                        }
                    }
                }
                view.onTouchEvent(motionEvent);
                return true;
            }
        });

        textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                String message = getTrimmedString(charSequence.toString());
                String template = TemplateSupport.getInstance().getTemplate(message);
                if ( template != null && template.compareToIgnoreCase("") != 0) {
                    FileLog.d("tsupport", message + "-->" + template);
                    messsageEditText.removeTextChangedListener(textWatcher);
                    messsageEditText.setText(template);
                    messsageEditText.addTextChangedListener(textWatcher);
                    message = template;
                }
                //sendButton.setEnabled(message.length() != 0);
                //checkSendButton();

                if (message.length() != 0 && lastTypingTimeSend < System.currentTimeMillis() - 5000 && !ignoreTextChange) {
                    int currentTime = ConnectionsManager.getInstance().getCurrentTime();
                    TLRPC.User currentUser = null;
                    if ((int)dialog_id > 0) {
                        currentUser = MessagesController.getInstance().users.get((int)dialog_id);
                    }
                    if (currentUser != null && currentUser.status != null && currentUser.status.expires < currentTime) {
                        return;
                    }
                    lastTypingTimeSend = System.currentTimeMillis();
                    if (delegate != null) {
                        delegate.needSendTyping();
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (sendByEnter && editable.length() > 0 && editable.charAt(editable.length() - 1) == '\n') {
                    sendMessage();
                }
                int i = 0;
                ImageSpan[] arrayOfImageSpan = editable.getSpans(0, editable.length(), ImageSpan.class);
                int j = arrayOfImageSpan.length;
                while (true) {
                    if (i >= j) {
                        Emoji.replaceEmoji(editable, messsageEditText.getPaint().getFontMetricsInt(), AndroidUtilities.dp(20));
                        return;
                    }
                    editable.removeSpan(arrayOfImageSpan[i]);
                    i++;
                }
            }
        };
        messsageEditText.addTextChangedListener(textWatcher);

        //checkSendButton();
    }

    private void sendMessage() {
        if (processSendingText(messsageEditText.getText().toString())) {
            NotificationCenter.getInstance().postNotificationName(MessagesController.readChatNotification);
            messsageEditText.setText("");
            lastTypingTimeSend = 0;
            if (delegate != null) {
                delegate.onMessageSend();
            }
        }
    }

    public boolean processSendingText(String text) {
        String templateText = TemplateSupport.getInstance().getTemplate(text); // Check in Template file
        if (templateText.compareToIgnoreCase("") != 0)
            text = getTrimmedString(templateText);
        else
            text = getTrimmedString(text);
        if (text.length() != 0) {
            int count = (int)Math.ceil(text.length() / 2048.0f);
            for (int a = 0; a < count; a++) {
                String mess = text.substring(a * 2048, Math.min((a + 1) * 2048, text.length()));
                MessagesController.getInstance().sendMessage(mess, dialog_id);
            }
            return true;
        }
        else { // Mark as read but send nothing
            NotificationCenter.getInstance().postNotificationName(MessagesController.readChatNotification);
            return false;
        }

    }

    private String getTrimmedString(String src) {
        String result = src.trim();
        if (result.length() == 0) {
            return result;
        }
        while (src.startsWith("\n")) {
            src = src.substring(1);
        }
        while (src.endsWith("\n")) {
            src = src.substring(0, src.length() - 1);
        }
        return src;
    }

    /*private void checkSendButton() {
        String message = getTrimmedString(messsageEditText.getText().toString());
        if (message.length() > 0) {
            sendButton.setVisibility(View.VISIBLE);
            audioSendButton.setVisibility(View.INVISIBLE);
        } else {
            sendButton.setVisibility(View.INVISIBLE);
            audioSendButton.setVisibility(View.VISIBLE);
        }
    }
*/
    private void updateAudioRecordIntefrace() {
        if (recordingAudio) {
            try {
                if (mWakeLock == null) {
                    PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "audio record lock");
                    mWakeLock.acquire();
                }
            } catch (Exception e) {
                FileLog.e("tdesktop", e);
            }
            AndroidUtilities.lockOrientation(parentActivity);

            recordPanel.setVisibility(View.VISIBLE);
            recordTimeText.setText("00:00");
            lastTimeString = null;
            if(android.os.Build.VERSION.SDK_INT > 13) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)slideText.getLayoutParams();
                params.leftMargin = AndroidUtilities.dp(30);
                slideText.setLayoutParams(params);
                slideText.setAlpha(1);
                recordPanel.setX(AndroidUtilities.displaySize.x);
                recordPanel.animate().setInterpolator(new AccelerateDecelerateInterpolator()).setListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animator) {
                    }

                    @Override
                    public void onAnimationEnd(Animator animator) {
                        recordPanel.setX(0);
                    }

                    @Override
                    public void onAnimationCancel(Animator animator) {
                    }

                    @Override
                    public void onAnimationRepeat(Animator animator) {
                    }
                }).setDuration(300).translationX(0).start();
            }
        } else {
            if (mWakeLock != null) {
                try {
                    mWakeLock.release();
                    mWakeLock = null;
                } catch (Exception e) {
                    FileLog.e("tdesktop", e);
                }
            }
            AndroidUtilities.unlockOrientation(parentActivity);
            if(android.os.Build.VERSION.SDK_INT > 13) {
                recordPanel.animate().setInterpolator(new AccelerateDecelerateInterpolator()).setListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animator) {

                    }

                    @Override
                    public void onAnimationEnd(Animator animator) {
                        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)slideText.getLayoutParams();
                        params.leftMargin = AndroidUtilities.dp(30);
                        slideText.setLayoutParams(params);
                        slideText.setAlpha(1);
                        recordPanel.setVisibility(View.GONE);
                    }

                    @Override
                    public void onAnimationCancel(Animator animator) {
                    }

                    @Override
                    public void onAnimationRepeat(Animator animator) {
                    }
                }).setDuration(300).translationX(AndroidUtilities.displaySize.x).start();
            } else {
                recordPanel.setVisibility(View.GONE);
            }
        }
    }

    private void showEmojiPopup(boolean show) {
        InputMethodManager localInputMethodManager = (InputMethodManager)ApplicationLoader.applicationContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (show) {
            if (emojiPopup == null) {
                createEmojiPopup();
            }
            int currentHeight;
            WindowManager manager = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Activity.WINDOW_SERVICE);
            int rotation = manager.getDefaultDisplay().getRotation();
            if (keyboardHeight <= 0) {
                keyboardHeight = ApplicationLoader.applicationContext.getSharedPreferences("emoji", 0).getInt("kbd_height", AndroidUtilities.dp(200));
            }
            if (keyboardHeightLand <= 0) {
                keyboardHeightLand = ApplicationLoader.applicationContext.getSharedPreferences("emoji", 0).getInt("kbd_height_land3", AndroidUtilities.dp(200));
            }
            if (rotation == Surface.ROTATION_270 || rotation == Surface.ROTATION_90) {
                currentHeight = keyboardHeightLand;
            } else {
                currentHeight = keyboardHeight;
            }
            emojiPopup.setHeight(View.MeasureSpec.makeMeasureSpec(currentHeight, View.MeasureSpec.EXACTLY));
            emojiPopup.setWidth(View.MeasureSpec.makeMeasureSpec(sizeNotifierRelativeLayout.getWidth(), View.MeasureSpec.EXACTLY));

            emojiPopup.showAtLocation(parentActivity.getWindow().getDecorView(), 83, 0, 0);
            if (!keyboardVisible) {
                sizeNotifierRelativeLayout.setPadding(0, 0, 0, currentHeight);
                emojiButton.setImageResource(R.drawable.ic_msg_panel_hide);
                return;
            }
            emojiButton.setImageResource(R.drawable.ic_msg_panel_kb);
            return;
        }
        if (emojiButton != null) {
            emojiButton.setImageResource(R.drawable.ic_msg_panel_smiles);
        }
        if (emojiPopup != null) {
            emojiPopup.dismiss();
        }
        if (sizeNotifierRelativeLayout != null) {
            sizeNotifierRelativeLayout.post(new Runnable() {
                public void run() {
                    if (sizeNotifierRelativeLayout != null) {
                        sizeNotifierRelativeLayout.setPadding(0, 0, 0, 0);
                    }
                }
            });
        }
    }

    public void hideEmojiPopup() {
        if (emojiPopup != null && emojiPopup.isShowing()) {
            showEmojiPopup(false);
        }
    }

    private void createEmojiPopup() {
        if (parentActivity == null) {
            return;
        }
        emojiView = new EmojiView(parentActivity);
        emojiView.setListener(new EmojiView.Listener() {
            public void onBackspace() {
                messsageEditText.dispatchKeyEvent(new KeyEvent(0, 67));
            }

            public void onEmojiSelected(String paramAnonymousString) {
                int i = messsageEditText.getSelectionEnd();
                CharSequence localCharSequence = Emoji.replaceEmoji(paramAnonymousString, messsageEditText.getPaint().getFontMetricsInt(), AndroidUtilities.dp(20));
                messsageEditText.setText(messsageEditText.getText().insert(i, localCharSequence));
                int j = i + localCharSequence.length();
                messsageEditText.setSelection(j, j);
            }
        });
        emojiPopup = new PopupWindow(emojiView);
    }

    public void setDelegate(ChatActivityEnterViewDelegate delegate) {
        this.delegate = delegate;
    }

    public void setDialogId(long id) {
        dialog_id = id;
    }

    public void setMarkAsReadParameters(ArrayList<MessageObject> messages, int minMessageId, int maxDate) {
        this.messages = messages;
        this.minMessageId = minMessageId;
        this.maxDate = maxDate;

    }

    public void setFieldText(String text) {
        ignoreTextChange = true;
        messsageEditText.setText(text);
        messsageEditText.setSelection(messsageEditText.getText().length());
        ignoreTextChange = false;
    }

    public void setFieldFocused(boolean focus) {
        if (messsageEditText == null) {
            return;
        }
        if (focus) {
            if (!messsageEditText.isFocused()) {
                messsageEditText.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (messsageEditText != null) {
                            messsageEditText.requestFocus();
                        }
                    }
                }, 600);
            }
        } else {
            if (messsageEditText.isFocused() && !keyboardVisible) {
                messsageEditText.clearFocus();
            }
        }
    }

    public boolean hasText() {
        return messsageEditText != null && messsageEditText.length() > 0;
    }

    public String getFieldText() {
        if (messsageEditText != null && messsageEditText.length() > 0) {
            return messsageEditText.getText().toString();
        }
        return null;
    }

    public boolean isEmojiPopupShowing() {
        return emojiPopup != null && emojiPopup.isShowing();
    }

    @Override
    public void onSizeChanged(int height) {
        Rect localRect = new Rect();
        parentActivity.getWindow().getDecorView().getWindowVisibleDisplayFrame(localRect);

        WindowManager manager = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Activity.WINDOW_SERVICE);
        if (manager == null || manager.getDefaultDisplay() == null) {
            return;
        }
        int rotation = manager.getDefaultDisplay().getRotation();

        if (height > AndroidUtilities.dp(50)) {
            if (rotation == Surface.ROTATION_270 || rotation == Surface.ROTATION_90) {
                keyboardHeightLand = height;
                ApplicationLoader.applicationContext.getSharedPreferences("emoji", 0).edit().putInt("kbd_height_land3", keyboardHeightLand).commit();
            } else {
                keyboardHeight = height;
                ApplicationLoader.applicationContext.getSharedPreferences("emoji", 0).edit().putInt("kbd_height", keyboardHeight).commit();
            }
        }

        if (emojiPopup != null && emojiPopup.isShowing()) {
            WindowManager wm = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
            final WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams)emojiPopup.getContentView().getLayoutParams();
            layoutParams.width = sizeNotifierRelativeLayout.getWidth();
            if (rotation == Surface.ROTATION_270 || rotation == Surface.ROTATION_90) {
                layoutParams.height = keyboardHeightLand;
            } else {
                layoutParams.height = keyboardHeight;
            }
            wm.updateViewLayout(emojiPopup.getContentView(), layoutParams);
            if (!keyboardVisible) {
                sizeNotifierRelativeLayout.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sizeNotifierRelativeLayout != null) {
                            sizeNotifierRelativeLayout.setPadding(0, 0, 0, layoutParams.height);
                            sizeNotifierRelativeLayout.requestLayout();
                        }
                    }
                });
            }
        }

        boolean oldValue = keyboardVisible;
        keyboardVisible = height > 0;
        if (keyboardVisible && sizeNotifierRelativeLayout.getPaddingBottom() > 0) {
            showEmojiPopup(false);
        } else if (!keyboardVisible && keyboardVisible != oldValue && emojiPopup != null && emojiPopup.isShowing()) {
            showEmojiPopup(false);
        }
    }

    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == 999) {
            if (emojiView != null) {
                emojiView.invalidateViews();
            }
        } else if (id == MediaController.recordProgressChanged) {
            Long time = (Long)args[0] / 1000;
            String str = String.format("%02d:%02d", time / 60, time % 60);
            if (lastTimeString == null || !lastTimeString.equals(str)) {
                if (recordTimeText != null) {
                    recordTimeText.setText(str);
                }
            }
        } else if (id == MessagesController.closeChats) {
            if (messsageEditText != null && messsageEditText.isFocused()) {
                AndroidUtilities.hideKeyboard(messsageEditText);
            }
        } else if (id == MediaController.recordStartError || id == MediaController.recordStopped) {
            if (recordingAudio) {
                recordingAudio = false;
                updateAudioRecordIntefrace();
            }
        } else if (id == MediaController.recordStarted) {
            if (!recordingAudio) {
                recordingAudio = true;
                updateAudioRecordIntefrace();
            }
        } else if (id == MediaController.audioDidSent) {
            if (delegate != null) {
                delegate.onMessageSend();
            }
        }
    }
}