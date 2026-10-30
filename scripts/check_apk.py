#!/usr/bin/env python3
"""Validate built APK payload and merged permission contract; no model weights."""
import pathlib, sys, zipfile, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
assert apk.is_file() and apk.stat().st_size>1_000_000, 'APK missing or implausibly small'
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None, 'Corrupt APK'
    assert 'AndroidManifest.xml' in z.namelist()
    assert 'classes.dex' in z.namelist()
    assert any('Hani_ctc/optical/conv_model.fb' in n for n in z.namelist()), 'Bundled Chinese OCR weights absent'
    assert any('libsqlcipher.so' in n for n in z.namelist()), 'SQLCipher native library absent'
    assert any(n.endswith('.so') and 'litert' in n.lower() for n in z.namelist()), 'LiteRT-LM native library absent'
    assert not any(n.lower().endswith(('.litertlm','.gguf')) for n in z.namelist()), 'Weights bundled'
manifest=root/'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml'
tree=ET.parse(manifest)
permissions={e.attrib.get('{http://schemas.android.com/apk/res/android}name') for e in tree.findall('uses-permission')}
assert 'android.permission.INTERNET' not in permissions
assert 'android.permission.ACCESS_NETWORK_STATE' not in permissions
assert 'android.permission.READ_EXTERNAL_STORAGE' not in permissions
assert 'android.permission.WRITE_EXTERNAL_STORAGE' not in permissions
assert tree.find('application').attrib['{http://schemas.android.com/apk/res/android}allowBackup']=='false'
print(f'PASS: APK {apk.stat().st_size:,} bytes; no network/storage permissions; no model weights; native OCR/AI/database payload packaged.')
