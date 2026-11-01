package org.telegram.messenger;

public class LanguageDetector {
    public interface StringCallback {
        void run(String str);
    }
    public interface ExceptionCallback {
        void run(Exception e);
    }

    public static boolean hasSupport() {
        return !org.mlmvpn.chat.bridge.Bridge.ENABLED; // MLMCHAT: no ML Kit (Google) in the spike
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail) {
        detectLanguage(text, onSuccess, onFail, false);
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail, boolean initializeFirst) {
        if (org.mlmvpn.chat.bridge.Bridge.ENABLED) { // MLMCHAT: no ML Kit (Google) in the spike
            if (onFail != null) {
                onFail.run(null);
            }
            return;
        }
        try {
            if (initializeFirst) {
                com.google.mlkit.common.sdkinternal.MlKitContext.zza(ApplicationLoader.applicationContext);
            }
            com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
                .identifyLanguage(text)
                .addOnSuccessListener(str -> {
                    if (onSuccess != null) {
                        onSuccess.run(str);
                    }
                })
                .addOnFailureListener(e -> {
                    if (onFail != null) {
                        onFail.run(e);
                    }
                });
        } catch (IllegalStateException e) {
            if (!initializeFirst) {
                detectLanguage(text, onSuccess, onFail, true);
            } else {
                if (onFail != null) {
                    onFail.run(e);
                }
                FileLog.e(e, false);
            }
        } catch (Exception e) {
            if (onFail != null) {
                onFail.run(e);
            }
            FileLog.e(e);
        } catch (Throwable t) {
            if (onFail != null) {
                onFail.run(null);
            }
            FileLog.e(t, false);
        }
    }
}
