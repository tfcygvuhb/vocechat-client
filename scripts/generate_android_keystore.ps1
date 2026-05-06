param(
  [string]$Out = "android\release\note-upload-key.jks"
)
$alias = if ($env:ANDROID_KEY_ALIAS) { $env:ANDROID_KEY_ALIAS } else { "note" }
$storePass = if ($env:ANDROID_KEYSTORE_PASSWORD) { $env:ANDROID_KEYSTORE_PASSWORD } else { "change-this-store-password" }
$keyPass = if ($env:ANDROID_KEY_PASSWORD) { $env:ANDROID_KEY_PASSWORD } else { "change-this-key-password" }
New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null
keytool -genkeypair -v `
  -storetype JKS `
  -keystore $Out `
  -storepass $storePass `
  -alias $alias `
  -keypass $keyPass `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000 `
  -dname "CN=note,O=note,C=CN"
Write-Host "Keystore created at $Out"
Write-Host "Base64 for GitHub secret ANDROID_KEYSTORE_BASE64:"
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $Out)))
