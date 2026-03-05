$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\Utilizador\AppData\Local\Android\Sdk'
$gradle = 'C:\Users\Utilizador\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat'
Set-Location 'C:\Moon\Murmur'
& $gradle assembleDebug 2>&1
