package app.brootlite.tv.kodi;

interface IPyDialogCallback {
    int onSelect(String heading, in List<String> options);
    String onInput(String heading, String defaultText, boolean hidden);
    boolean onYesNo(String heading, String message, String noLabel, String yesLabel);
    void onNotification(String heading, String message);
    void onTextBox(String heading, String body);
}
