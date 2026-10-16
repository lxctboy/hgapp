"""生成 Android 正式签名 keystore（PKCS12）。

本机没有 JDK / keytool,因此用 cryptography 直接生成等价密钥库。
产出的 .p12 与 keytool 生成的签名材料完全等价:

    keytool -genkeypair -alias hgapp -keyalg RSA -keysize 2048 -validity 10000

APK 签名由 apksigner 完成,它支持 PKCS12 与 JKS 两种库格式,
build.gradle 里按扩展名自动切换 storeType。
"""

import getpass
import secrets
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

OUT_DIR = Path(r"D:/project/keystores")
STORE_PATH = OUT_DIR / "hgapp.p12"
SUMMARY_PATH = OUT_DIR / "hgapp-signing.txt"

ALIAS = "hgapp"
KEY_SIZE = 2048
# 10000 天 ≈27 年,超过应用商店对证书有效期的要求(通常要求 2033 年以后)
VALID_DAYS = 10000

SUBJECT = x509.Name([
    x509.NameAttribute(NameOID.COUNTRY_NAME, "CN"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "HuangGuo"),
    x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "Development"),
    x509.NameAttribute(NameOID.COMMON_NAME, "ChengZi Short Drama"),
])


def gen_secret() -> str:
    return secrets.token_urlsafe(24)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    if STORE_PATH.exists():
        sys.exit(f"[!] {STORE_PATH} 已存在,未覆盖。确认后可手动删除重跑。")

    store_pass = gen_secret()
    key_pass = gen_secret()

    print("[1/4] 生成 RSA 密钥对 ...")
    key = rsa.generate_private_key(public_exponent=65537, key_size=KEY_SIZE)

    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(SUBJECT)
        .issuer_name(SUBJECT)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5))
        .not_valid_after(now + timedelta(days=VALID_DAYS))
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .add_extension(
            x509.BasicConstraints(ca=True, path_length=None),
            critical=True,
        )
        .sign(private_key=key, algorithm=hashes.SHA256())
    )

    print("[2/4] 打包为 PKCS12 密钥库 ...")
    p12 = pkcs12.serialize_key_and_certificates(
        name=ALIAS.encode(),
        key=key,
        cert=cert,
        cas=[cert],
        encryption_algorithm=serialization.BestAvailableEncryption(store_pass.encode()),
    )
    STORE_PATH.write_bytes(p12)
    try:
        STORE_PATH.chmod(0o600)
    except OSError:
        pass

    print("[3/4] 回填校验 ...")
    loaded_key, loaded_cert, _ = pkcs12.load_key_and_certificates(
        STORE_PATH.read_bytes(), store_pass.encode()
    )
    assert loaded_cert is not None and loaded_key is not None
    if not isinstance(loaded_key, rsa.RSAPrivateKey):
        sys.exit("[!] 回填校验失败:私钥类型不匹配")
    if loaded_cert.subject != SUBJECT:
        sys.exit("[!] 回填校验失败:证书主题不匹配")
    print("      回填校验通过")

    print("[4/4] 写出凭据清单 ...")
    import base64
    b64 = base64.b64encode(STORE_PATH.read_bytes()).decode()
    SUMMARY_PATH.write_text(
        "\n".join([
            "# 橙子短剧 Android 正式签名密钥 (务必妥善保管,丢失将无法更新应用)",
            f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
            f"密钥库文件: {STORE_PATH}",
            f"有效期截止: {(now + timedelta(days=VALID_DAYS)).date()}",
            "",
            "--- GitHub Secret 配置值 ---",
            "",
            f"ANDROID_KEYSTORE_PASSWORD = {store_pass}",
            f"ANDROID_KEY_ALIAS         = {ALIAS}",
            f"ANDROID_KEY_PASSWORD      = {key_pass}",
            "",
            "--- ANDROID_KEYSTORE_BASE64 (整行复制) ---",
            "",
            b64,
            "",
        ]),
        encoding="utf-8",
    )
    try:
        SUMMARY_PATH.chmod(0o600)
    except OSError:
        pass

    print()
    print("=" * 60)
    print(f"密钥库: {STORE_PATH}")
    print("凭据清单(含可直接粘贴的 Secret):")
    print(f"  {SUMMARY_PATH}")
    print("=" * 60)


if __name__ == "__main__":
    main()
