@echo off
setlocal
set JAVA_HOME=C:\dsh-app-build\jdk\jdk-21.0.12.1+1
set SDK=C:\dsh-app-build\sdk
set BT=%SDK%\build-tools\35.0.0
set AJAR=%SDK%\platforms\android-34\android.jar
cd /d "%~dp0"

echo [1/6] compiling java
if exist classes rmdir /s /q classes
mkdir classes
"%JAVA_HOME%\bin\javac" -source 17 -target 17 -nowarn -classpath "%AJAR%;C:\dsh-app-build\zxing.jar%" -d classes src\com\dsh\pocket\*.java
if errorlevel 1 exit /b 1

echo [2/6] dexing
if exist dexout rmdir /s /q dexout
mkdir dexout
call "%BT%\d8.bat" --lib "%AJAR%" --min-api 26 --output dexout classes\com\dsh\pocket\*.class C:\dsh-app-build\zxing.jar
if errorlevel 1 exit /b 1

echo [3/6] compiling + linking resources
if exist res-compiled.zip del res-compiled.zip
call "%BT%\aapt2.exe" compile --dir res -o res-compiled.zip
if errorlevel 1 exit /b 1
if exist app-unsigned.apk del app-unsigned.apk
call "%BT%\aapt2.exe" link -o app-unsigned.apk -I "%AJAR%" --manifest AndroidManifest.xml -R res-compiled.zip --min-sdk-version 26 --target-sdk-version 34
if errorlevel 1 exit /b 1

echo [4/6] packing dex
pushd dexout
"%JAVA_HOME%\bin\jar" uf ..\app-unsigned.apk classes.dex
if errorlevel 1 (popd & exit /b 1)
popd

echo [5/6] aligning
if exist app-aligned.apk del app-aligned.apk
call "%BT%\zipalign.exe" -f 4 app-unsigned.apk app-aligned.apk
if errorlevel 1 exit /b 1

echo [6/6] signing
if not exist dsh.jks "%JAVA_HOME%\bin\keytool" -genkeypair -keystore dsh.jks -alias dsh -keyalg RSA -keysize 2048 -validity 10000 -storepass dshpocket -keypass dshpocket -dname "CN=DSH Pocket,O=Pocket,C=CN"
if exist dsh-pocket.apk del dsh-pocket.apk
call "%BT%\apksigner.bat" sign --ks dsh.jks --ks-pass pass:dshpocket --key-pass pass:dshpocket --out dsh-pocket.apk app-aligned.apk
if errorlevel 1 exit /b 1

echo BUILD OK
dir dsh-pocket.apk
endlocal
