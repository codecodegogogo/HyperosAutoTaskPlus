import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * 用 apksig 库做标准 v1+v2+v3 签名，替代缺失的 apksigner CLI。
 * 用法: java -cp apksig.jar;. Signer in.apk out.apk keystore.p12 storepass alias keypass
 */
public class Signer {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("usage: Signer <in.apk> <out.apk> <keystore.p12> <storepass> <alias> <keypass>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(args[2])) {
            ks.load(fis, args[3].toCharArray());
        }
        String alias = args[4];
        PrivateKey key = (PrivateKey) ks.getKey(alias, args[5].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        if (key == null || cert == null) {
            throw new IllegalStateException("keystore 里没有别名 " + alias);
        }

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "CERT", key, Collections.singletonList(cert)).build();

        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(31)
                .build()
                .sign();

        System.out.println("signed: " + out.getAbsolutePath());
    }
}
