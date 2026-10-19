package com.company.campaign.security.data;

import java.util.Set;

/**
 * Field-name based sensitivity classification used by {@link Redaction}.
 *
 * <p>Matching is deliberately loose: names are lower-cased and every separator removed, so
 * {@code memberId}, {@code member_id}, {@code MEMBER-ID} and {@code member.id} all match. Rejecting and
 * redacting a legitimate field is the safe direction; missing an alias would leak a secret or PII.
 *
 * <p>Names are only a backstop. A value that is genuinely restricted must also be classified
 * {@link DataClassification#RESTRICTED}, which excludes it from the model prompt regardless of its name.
 */
public final class SensitiveFieldClassifier {

    /** Sensitivity of a field name. */
    public enum Sensitivity {
        NONE,
        SECRET,
        PII
    }

    private static final Set<String> SECRET_NAMES = Set.of(
            "password", "passwd", "pwd", "secret", "clientsecret", "apikey", "privatekey",
            "token", "accesstoken", "refreshtoken", "idtoken", "authtoken", "bearertoken", "bearer",
            "authorization", "authorizationheader", "authheader", "credential", "credentials",
            "cookie", "setcookie", "sessionkey");

    private static final Set<String> PII_NAMES = Set.of(
            "memberid", "externalmemberid", "member", "membername", "membersample", "membersamples", "sample",
            "email", "emailaddress", "contactemail",
            "phone", "phonenumber", "contactphone", "mobile", "mobilenumber", "contactmobile", "telephone",
            "msisdn", "nationalid", "ssn", "passportnumber", "dateofbirth", "dob",
            "address", "homeaddress", "ipaddress");

    private SensitiveFieldClassifier() {
    }

    public static Sensitivity classify(String fieldName) {
        String normalized = normalize(fieldName);
        if (normalized.isEmpty()) {
            return Sensitivity.NONE;
        }
        if (SECRET_NAMES.contains(normalized)) {
            return Sensitivity.SECRET;
        }
        if (PII_NAMES.contains(normalized)) {
            return Sensitivity.PII;
        }
        return Sensitivity.NONE;
    }

    public static boolean isSecret(String fieldName) {
        return classify(fieldName) == Sensitivity.SECRET;
    }

    public static boolean isPii(String fieldName) {
        return classify(fieldName) == Sensitivity.PII;
    }

    /** Locale-independent normalization so the decision cannot depend on the default locale. */
    static String normalize(String fieldName) {
        if (fieldName == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(fieldName.length());
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }
}