param([string]$Out='note-upload-key.jks',[string]$Alias='note',[string]$StorePass='change-me',[string]$KeyPass='change-me')
keytool -genkeypair -v -storetype JKS -keystore $Out -storepass $StorePass -alias $Alias -keypass $KeyPass -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=note,O=note,C=CN"
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $Out))) | Set-Content "$Out.base64.txt"
Write-Host "Generated $Out and $Out.base64.txt"
