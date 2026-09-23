#!/usr/bin/env python3
"""Generate PadNote.xcodeproj without xcodegen or third party modules."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "PadNote.xcodeproj"
PBX = PROJECT / "project.pbxproj"

def ident(label):
    return hashlib.sha1(label.encode()).hexdigest().upper()[:24]

def q(value):
    return '"' + value.replace('"', '\\"') + '"'

def main():
    app_sources = sorted((ROOT / "PadNote").rglob("*.swift"))
    test_sources = sorted((ROOT / "PadNoteTests").rglob("*.swift"))
    ui_sources = sorted((ROOT / "PadNoteUITests").rglob("*.swift"))
    app_target = ident("target:PadNote")
    unit_target = ident("target:PadNoteTests")
    ui_target = ident("target:PadNoteUITests")
    app_product = ident("product:PadNote")
    unit_product = ident("product:PadNoteTests")
    ui_product = ident("product:PadNoteUITests")
    project_id = ident("project")
    main_group = ident("group:main")
    products_group = ident("group:products")
    resources_ref = ident("resource:Web")
    objects = []
    def obj(kind, body, key): objects.append((key, f"{key} /* {key} */ = {{\n{body}\n\t}};"))
    def file_ref(path, file_type="sourcecode.swift"):
        key = ident("file:" + path)
        obj("PBXFileReference", f"\t\tisa = PBXFileReference;\n\t\tlastKnownFileType = {file_type};\n\t\tpath = {q(path)};\n\t\tsourceTree = \"<group>\";", key)
        return key
    def build_file(ref, target):
        key = ident(f"build:{target}:{ref}")
        obj("PBXBuildFile", f"\t\tisa = PBXBuildFile;\n\t\tfileRef = {ref};", key)
        return key
    app_build, app_refs = [], []
    for source in app_sources:
        rel = source.relative_to(ROOT / "PadNote").as_posix(); ref = file_ref(rel); app_refs.append(ref); app_build.append(build_file(ref, "app"))
    unit_build, unit_refs = [], []
    for source in test_sources:
        rel = source.relative_to(ROOT / "PadNoteTests").as_posix(); ref = file_ref(rel); unit_refs.append(ref); unit_build.append(build_file(ref, "unit"))
    ui_build, ui_refs = [], []
    for source in ui_sources:
        rel = source.relative_to(ROOT / "PadNoteUITests").as_posix(); ref = file_ref(rel); ui_refs.append(ref); ui_build.append(build_file(ref, "ui"))
    obj("PBXFileReference", "\t\tisa = PBXFileReference;\n\t\tlastKnownFileType = folder;\n\t\tname = Web;\n\t\tpath = \"PadNote/Resources/Web\";\n\t\tsourceTree = \"<group>\";", resources_ref)
    asset_ref = None
    asset_catalog = ROOT / "PadNote/Resources/Assets.xcassets"
    if asset_catalog.exists():
        asset_ref = ident("resource:Assets.xcassets")
        obj("PBXFileReference", "\t\tisa = PBXFileReference;\n\t\tlastKnownFileType = folder.assetcatalog;\n\t\tpath = \"PadNote/Resources/Assets.xcassets\";\n\t\tsourceTree = \"<group>\";", asset_ref)
    app_product_ref = ident("productref:PadNote"); unit_product_ref = ident("productref:PadNoteTests"); ui_product_ref = ident("productref:PadNoteUITests")
    for key, name, typ in [(app_product_ref, "PadNote.app", "wrapper.application"), (unit_product_ref, "PadNoteTests.xctest", "wrapper.cfbundle"), (ui_product_ref, "PadNoteUITests.xctest", "wrapper.cfbundle")]:
        obj("PBXFileReference", f"\t\tisa = PBXFileReference;\n\t\texplicitFileType = {typ};\n\t\tincludeInIndex = 0;\n\t\tpath = {q(name)};\n\t\tsourceTree = BUILT_PRODUCTS_DIR;", key)
    app_res_build = [build_file(resources_ref, "resources")]
    if asset_ref: app_res_build.append(build_file(asset_ref, "resources"))
    app_sources_phase = ident("phase:sources:app"); unit_sources_phase = ident("phase:sources:unit"); ui_sources_phase = ident("phase:sources:ui"); app_res_phase = ident("phase:resources:app"); app_frameworks = ident("phase:frameworks:app"); unit_frameworks = ident("phase:frameworks:unit"); ui_frameworks = ident("phase:frameworks:ui"); app_copy = ident("phase:copy:app"); unit_copy = ident("phase:copy:unit"); ui_copy = ident("phase:copy:ui")
    def refs_text(refs): return "".join("\n\t\t\t" + ref + " /* " + ref + " */," for ref in refs)
    for key, files in [(app_sources_phase, app_build), (unit_sources_phase, unit_build), (ui_sources_phase, ui_build), (app_res_phase, app_res_build), (app_frameworks, []), (unit_frameworks, []), (ui_frameworks, []), (app_copy, []), (unit_copy, []), (ui_copy, [])]:
        kind = "PBXResourcesBuildPhase" if key == app_res_phase else "PBXSourcesBuildPhase" if key in (app_sources_phase, unit_sources_phase, ui_sources_phase) else "PBXFrameworksBuildPhase"
        obj(kind, f"\t\tisa = {kind};\n\t\tbuildActionMask = 2147483647;\n\t\tfiles = ({refs_text(files)}\n\t\t);\n\t\trunOnlyForDeploymentPostprocessing = 0;", key)
    def settings(product, kind, debug, host=False):
        values = [f"PRODUCT_NAME = {q(product)}", f"PRODUCT_BUNDLE_IDENTIFIER = {q('com.padnote.ipad' if kind == 'app' else 'com.padnote.ipad.' + kind)}", "SUPPORTED_PLATFORMS = (iphonesimulator, iphoneos)", "SWIFT_VERSION = 5.0", "IPHONEOS_DEPLOYMENT_TARGET = 17.0", "TARGETED_DEVICE_FAMILY = 2", "CODE_SIGN_STYLE = Automatic", "CLANG_ENABLE_MODULES = YES", "LD_RUNPATH_SEARCH_PATHS = (\"$(inherited)\", \"@executable_path/Frameworks\")"]
        if kind == "app": values += ["INFOPLIST_FILE = \"PadNote/Info.plist\"", "ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon"]
        else: values += ["GENERATE_INFOPLIST_FILE = YES"]
        if host: values += ["TEST_HOST = \"$(BUILT_PRODUCTS_DIR)/PadNote.app/PadNote\"", "BUNDLE_LOADER = \"$(TEST_HOST)\""]
        if kind == "ui": values += ["TEST_TARGET_NAME = PadNote"]
        if debug: values += ["SWIFT_OPTIMIZATION_LEVEL = -Onone", "SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG", "ENABLE_TESTABILITY = YES"]
        else: values += ["SWIFT_OPTIMIZATION_LEVEL = -O"]
        return "\n".join("\t\t\t\t" + value + ";" for value in values)
    project_cfg_debug, project_cfg_release = ident("cfg:project:debug"), ident("cfg:project:release")
    for key, name in [(project_cfg_debug, "Debug"), (project_cfg_release, "Release")]: obj("XCBuildConfiguration", f"\t\tisa = XCBuildConfiguration;\n\t\tbuildSettings = {{\n\t\t}};\n\t\tname = {name};", key)
    configs = ident("configs:project"); obj("XCConfigurationList", f"\t\tisa = XCConfigurationList;\n\t\tbuildConfigurations = ({project_cfg_debug}, {project_cfg_release});\n\t\tdefaultConfigurationIsVisible = 0;\n\t\tdefaultConfigurationName = Release;", configs)
    app_group = ident("group:app"); test_group = ident("group:test"); ui_group = ident("group:ui")
    obj("PBXGroup", f"\t\tisa = PBXGroup;\n\t\tchildren = ({refs_text(app_refs)}\n\t\t);\n\t\tpath = PadNote;\n\t\tsourceTree = \"<group>\";", app_group)
    obj("PBXGroup", f"\t\tisa = PBXGroup;\n\t\tchildren = ({refs_text(unit_refs)}\n\t\t);\n\t\tpath = PadNoteTests;\n\t\tsourceTree = \"<group>\";", test_group)
    obj("PBXGroup", f"\t\tisa = PBXGroup;\n\t\tchildren = ({refs_text(ui_refs)}\n\t\t);\n\t\tpath = PadNoteUITests;\n\t\tsourceTree = \"<group>\";", ui_group)
    obj("PBXGroup", f"\t\tisa = PBXGroup;\n\t\tchildren = ({app_product_ref},{unit_product_ref},{ui_product_ref}\n\t\t);\n\t\tname = Products;\n\t\tsourceTree = \"<group>\";", products_group)
    asset_child = "," + asset_ref if asset_ref else ""
    obj("PBXGroup", f"\t\tisa = PBXGroup;\n\t\tchildren = ({app_group},{test_group},{ui_group},{resources_ref}{asset_child},{products_group}\n\t\t);\n\t\tsourceTree = \"<group>\";", main_group)
    def target(name, product, product_ref, sources, frameworks, resources=None, kind="app", host=False, dependency=None):
        phase = ident("phase:" + name); debug_cfg = ident("cfg:" + name + ":debug"); release_cfg = ident("cfg:" + name + ":release")
        for key, config_name, debug in [(debug_cfg, "Debug", True), (release_cfg, "Release", False)]: obj("XCBuildConfiguration", f"\t\tisa = XCBuildConfiguration;\n\t\tbuildSettings = {{\n{settings(name if kind != 'app' else 'PadNote', kind, debug, host)}\n\t\t}};\n\t\tname = {config_name};", key)
        cfg = ident("cfgs:" + name); obj("XCConfigurationList", f"\t\tisa = XCConfigurationList;\n\t\tbuildConfigurations = ({debug_cfg}, {release_cfg});\n\t\tdefaultConfigurationIsVisible = 0;\n\t\tdefaultConfigurationName = Release;", cfg)
        dependency_text = "()" if dependency is None else "(" + ident("dependency:" + name) + ",)"
        if dependency is not None:
            dep_id = ident("dependency:" + name)
            obj("PBXTargetDependency", f"\t\tisa = PBXTargetDependency;\n\t	target = {dependency};", dep_id)
        obj("PBXNativeTarget", f"\t\tisa = PBXNativeTarget;\n\t\tbuildConfigurationList = {cfg};\n\t\tbuildPhases = ({sources},{frameworks}{',' + resources if resources else ''});\n\t\tbuildRules = ();\n\t\tdependencies = {dependency_text};\n\t\tname = {q(name)};\n\t\tproductName = {q(name)};\n\t\tproductReference = {product_ref};\n\t\tproductType = {q(product)};", phase)
        return phase
    app_native = target("PadNote", "com.apple.product-type.application", app_product_ref, app_sources_phase, app_frameworks, app_res_phase, "app")
    unit_native = target("PadNoteTests", "com.apple.product-type.bundle.unit-test", unit_product_ref, unit_sources_phase, unit_frameworks, kind="unit", host=True, dependency=app_native)
    ui_native = target("PadNoteUITests", "com.apple.product-type.bundle.ui-testing", ui_product_ref, ui_sources_phase, ui_frameworks, kind="ui", dependency=app_native)
    obj("PBXProject", f"\t\tisa = PBXProject;\n\t\tbuildConfigurationList = {configs};\n\t\tcompatibilityVersion = \"Xcode 16.0\";\n\t\tdevelopmentRegion = en;\n\t\tknownRegions = (en, Base);\n\t\tmainGroup = {main_group};\n\t\tproductRefGroup = {products_group};\n\t\ttargets = ({app_native}, {unit_native}, {ui_native});", project_id)
    PROJECT.mkdir(parents=True, exist_ok=True)
    lines = ['// !$*UTF8*$!', '{', '\tarchiveVersion = 1;', '\tclasses = {};', '\tobjectVersion = 56;', '\tobjects = {']
    lines.extend(v for _, v in sorted(objects)); lines.extend(['\t};', '\trootObject = ' + project_id + ';', '}'])
    PBX.write_text('\n'.join(lines) + '\n')

if __name__ == "__main__": main()
