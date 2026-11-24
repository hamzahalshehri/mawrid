package mawrid;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Salted PBKDF2 password hashes, stored as "salt:hash" (NFR-2.1). */
public class Auth {
    static byte[] pbkdf2(String p, byte[] salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(p.toCharArray(), salt, 120_000, 256)).getEncoded();
    }

    static String hash(String p) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Base64.Encoder e = Base64.getEncoder();
        return e.encodeToString(salt) + ":" + e.encodeToString(pbkdf2(p, salt));
    }

    static boolean check(String p, String stored) throws Exception {
        String[] t = stored.split(":");
        Base64.Decoder d = Base64.getDecoder();
        return MessageDigest.isEqual(pbkdf2(p, d.decode(t[0])), d.decode(t[1]));
    }
}
