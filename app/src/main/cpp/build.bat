@echo off
setlocal EnableDelayedExpansion

cmake --preset android-release
if %ERRORLEVEL% neq 0 goto :error

cmake --build --preset android-release
if %ERRORLEVEL% neq 0 goto :error

if not exist lib mkdir lib
xcopy /Y /I .\build\android\qnnlibs\*.so .\lib\
if %ERRORLEVEL% neq 0 goto :error

xcopy /Y /I .\build\android\bin\arm64-v8a\libstable_diffusion_core.so .\lib\
if %ERRORLEVEL% neq 0 goto :error

if not exist ..\jniLibs\arm64-v8a mkdir ..\jniLibs\arm64-v8a
xcopy /Y /I .\lib\*.so ..\jniLibs\arm64-v8a\
if %ERRORLEVEL% neq 0 goto :error

echo Build completed successfully
goto :eof

:error
echo Failed with error #%ERRORLEVEL%.
exit /b %ERRORLEVEL%
