@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\Utilizador\AppData\Local\Android\Sdk
set GRADLE=C:\Users\Utilizador\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat
call "%GRADLE%" assembleDebug > C:\Moon\Murmur\build_output.txt 2>&1
