"""Check that the .env the app writes on Change password works in Hermes itself (never the real one).

Run after `:shared:desktopTest` (DashboardPasswordTest leaves shared/build/test-dashboard-auth/.env with a TEST password):
    python -I tools/check-dashboard-hash.py
It copies that .env into a fresh temp HERMES_HOME, loads it with Hermes' own dotenv loader, builds the provider with the
basic-auth plugin's `_settings()` and logs in with `complete_password_login`. No gateway is started.
"""
import os, pathlib, shutil, sys, tempfile

app = pathlib.Path(__file__).resolve().parent.parent
src = app / "shared" / "build" / "test-dashboard-auth" / ".env"
home = pathlib.Path(tempfile.mkdtemp(prefix="hermes-home-test-"))
shutil.copy(src, home / ".env")
os.environ["HERMES_HOME"] = str(home)
for k in list(os.environ):
    if k.startswith("HERMES_DASHBOARD_BASIC_AUTH_"):
        del os.environ[k]
sys.path.insert(0, str(pathlib.Path(os.environ["LOCALAPPDATA"]) / "hermes" / "hermes-agent"))

from hermes_cli.env_loader import load_hermes_dotenv  # noqa: E402
from hermes_cli.dashboard_auth import InvalidCredentialsError  # noqa: E402
from plugins.dashboard_auth.basic import BasicAuthProvider, _settings  # noqa: E402

load_hermes_dotenv(hermes_home=home, load_external_secrets=False)
assert "HERMES_DASHBOARD_BASIC_AUTH_PASSWORD" not in os.environ, "plaintext still loaded"
kw = _settings()
assert kw["password_hash"] == os.environ["HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH"], "hash not taken from .env"
provider = BasicAuthProvider(**kw)
provider.complete_password_login(username="tester", password="Test-pass-1234!")
for wrong in ("old-pass", "Test-pass-1234"):
    try:
        provider.complete_password_login(username="tester", password=wrong)
        raise SystemExit(f"FAIL: {wrong!r} accepted")
    except InvalidCredentialsError:
        pass
shutil.rmtree(home, ignore_errors=True)
print("OK: Hermes loads the app-written .env, uses its hash, accepts the new password, rejects the old one")
