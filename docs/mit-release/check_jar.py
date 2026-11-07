"""Release archive checks, independent of Gradle success. Run with Python 3.11+."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import tomllib
import zipfile

jar = Path(sys.argv[1] if len(sys.argv) > 1 else "build/libs/recipeassistant-0.8.2.jar")
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), "duplicate resources"
    metadata = tomllib.loads(archive.read("META-INF/mods.toml").decode())
    assert metadata["mods"][0]["modId"] == "ctirecipeassistant"
    assert metadata["mods"][0]["version"] == "0.8.2"
    assert metadata["license"] == "MIT"
    assert metadata["mods"][0]["authors"] == "Co1dSource"
    assert metadata["mods"][0]["displayName"] == "Recipe Assistant"
    license_text = Path("LICENSE").read_text(encoding="utf-8")
    standard = Path("src/main/resources/META-INF/QUICK-CRAFT-LICENSE.txt").read_text(encoding="utf-8-sig")
    standard = standard.replace("Copyright (c) 2026 Sxilverrr", "Copyright (c) 2026 Co1dSource")
    assert license_text.split() == standard.split(), "MIT text changed beyond copyright holder"
    assert archive.read("META-INF/LICENSE") == Path("LICENSE").read_bytes(), "embedded license differs"
    notices = archive.read("META-INF/THIRD-PARTY-NOTICES.txt").decode()
    assert all(mod in notices for mod in ("JEI", "AE2", "ExtendedAE"))
    assert metadata["mods"][0]["displayTest"] == "IGNORE_SERVER_VERSION"
    dependencies = {d["modId"]: d for d in metadata["dependencies"]["ctirecipeassistant"]}
    for mod, required, version in [("jei", True, "[11.6.0.1024]"), ("ae2", False, "[12.9.9]"), ("expatternprovider", False, "[1.19-0.2.2-forge]")]:
        assert dependencies[mod]["mandatory"] is required
        assert dependencies[mod]["versionRange"] == version
    assert dependencies["minecraft"]["versionRange"] == "[1.19.2]"
    assert dependencies["forge"]["versionRange"] == "[43.4.12,44)"
    classes = [n for n in names if n.endswith(".class")]
    for name in classes:
        assert name.startswith("com/cti/ctirecipeassistant/"), ("third party class", name)
        code = archive.read(name)
        assert struct.unpack(">H", code[6:8])[0] == 61, ("not Java 17", name)
        if "/integration/ae2/" not in name:
            assert b"appeng/" not in code, ("core AE2 type reference", name)
        else:
            assert b"net/minecraftforge/fml/common/Mod$EventBusSubscriber" not in code, ("unconditional AE2 registration", name)
    assert not any(not n.endswith("/") and n.startswith("assets/") and not n.startswith("assets/ctirecipeassistant/") for n in names), "third party resource namespace"
    assert not any(n.endswith((".java", ".jar", ".log")) for n in names), "development files or nested jar"
    configs = ["ctirecipeassistant.mixins.json", "ctirecipeassistant.ae2.mixins.json"]
    declared = []
    for config in configs:
        data = json.loads(archive.read(config))
        package = data["package"].replace(".", "/") + "/"
        mixins = {package + n.replace(".", "/") + ".class" for n in data["client"]}
        for name in mixins:
            assert name in names, ("missing mixin class", name)
        ordinary = {n for n in classes if n.startswith(package)} - mixins - {package + "OptionalMixinPlugin.class"}
        assert not ordinary, ("mixin package captures product classes", ordinary)
        if "ae2" in config: assert data["plugin"].endswith("OptionalMixinPlugin")
        declared.append(config)
    manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\n ", "")
    assert all(config in manifest for config in declared)
    assert "Implementation-Title: Recipe Assistant" in manifest
    for language in ("zh_cn", "en_us"):
        translations = json.loads(archive.read(f"assets/ctirecipeassistant/lang/{language}.json"))
        assert translations["key.categories.ctirecipeassistant"] == "Recipe Assistant"
    assert "CTI" not in archive.read("pack.mcmeta").decode()
    assert "META-INF/QUICK-CRAFT-LICENSE.txt" in names
    assert "META-INF/THIRD-PARTY-NOTICES.txt" in names
result = {"jar": str(jar.resolve()), "bytes": jar.stat().st_size, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "classes": len(classes), "status": "PASS"}
print(json.dumps(result, ensure_ascii=False, indent=2))
