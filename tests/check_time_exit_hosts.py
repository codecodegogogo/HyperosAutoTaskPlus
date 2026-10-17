"""Read-only contract checks against locally supplied Security APKs (requires androguard)."""
from pathlib import Path
from zipfile import ZipFile
from loguru import logger

logger.remove()
from androguard.core.dex import DEX


ROOT = Path(__file__).resolve().parents[1]
VERSIONS = {
    "12.3.5": ("12.3.5", "Lb2/j;", "t", "s", "R0", "k0", "u", "p", "q", "b1"),
    "12.3.2": ("40001232", "Lb2/j;", "t", "s", "R0", "k0", "u", "p", "q", "b1"),
    "12.8.6": ("40001286", "LU1/j;", "t", "s", "T0", "k0", "u", "p", "q", "d1"),
    "13.5.9": ("40001359", "Lh6/m;", "x", "w", "Z0", "p0", "y", "t", "u", "o1"),
    "13.6.0": ("40001360", "Lh6/m;", "x", "w", "Z0", "p0", "y", "t", "u", "o1"),
}
TIME = "Lcom/miui/autotask/taskitem/CustomTimeConditionItem;"
ITEM = "Lcom/miui/autotask/taskitem/TaskItem;"
BEAN = "Lcom/miui/autotask/bean/r;"
SERVICE = "Lcom/miui/ai/service/OperationListCollectService;"


def body(method):
    return "\n".join(instruction.get_output() for instruction in method.get_instructions())


def methods(cls):
    return {(method.get_name(), method.get_descriptor().replace(" ", "")): method
            for method in cls.get_methods()}


def verify(version, config):
    marker, engine, reset, start, expired, dispatch, load, register, address, unregister = config
    apks = list((ROOT / "apk").glob(f"*{marker}*.apk"))
    assert len(apks) == 1, f"{version}: expected one local APK"
    with ZipFile(apks[0]) as archive:
        classes = {cls.get_name(): cls for cls in DEX(archive.read("classes2.dex")).get_classes()}
    native = methods(classes[engine])
    for name, signature in ((reset, f"({TIME})V"), (start, f"({TIME})V"), (expired, f"({TIME})Z"),
                            (dispatch, "(Ljava/lang/String;)V"), (load, f"({BEAN}Z)V"),
                            (register, f"({ITEM})V"), (address, "(Lcom/miui/autotask/taskitem/AddressTaskItem;)V"),
                            (unregister, "(Ljava/lang/String;Ljava/util/List;)V")):
        assert (name, signature) in native, f"{version}: missing {name}{signature}"
    assert "addNextResetAlarm endTime" in body(native[reset, f"({TIME})V"])
    assert "addNextAlarm day" in body(native[start, f"({TIME})V"])
    assert "isExpired endTime" in body(native[expired, f"({TIME})Z"])
    recovery = body(native[load, f"({BEAN}Z)V"])
    assert "find executed time condition task" in recovery
    assert f"->{expired}({TIME})Z" in recovery and f"->{reset}({TIME})V" in recovery
    assert f"->{register}({ITEM})V" in recovery and "->N " in recovery
    callback = body(native[dispatch, "(Ljava/lang/String;)V"])
    assert f"{BEAN}->q()Z" in callback and "->q " in callback
    service = methods(classes[SERVICE])
    on_start = body(service["onStartCommand", "(Landroid/content/Intent;II)I"])
    assert "TYPE_MEET_EXIT_TIME_CONDITION" in on_start and f"{engine}->{dispatch}(Ljava/lang/String;)V" in on_start
    fields = {field.get_name(): field.get_descriptor() for field in classes[engine].get_fields()}
    assert fields["q"] == "Ljava/util/concurrent/ConcurrentHashMap;" and "N" in fields
    time_methods = methods(classes[TIME])
    for method in ("u", "y", "z"):
        assert (method, "()I") in time_methods
    assert "final" not in classes[TIME].get_access_flags_string()
    print(f"PASS {version}: native time scheduling, exit dispatch, recovery and subclass contract")


if __name__ == "__main__":
    for version, config in VERSIONS.items():
        verify(version, config)
