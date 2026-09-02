@echo off
:: 自动定位到当前脚本所在目录下的 native\ngx_shim
set "SHIM_DIR=%~dp0native\ngx_shim"

echo 目标编译目录: %SHIM_DIR%
if not exist "%SHIM_DIR%\CMakeLists.txt" (
    echo 错误：找不到 CMakeLists.txt，确认路径 native\ngx_shim 存在！
    pause
    exit /b 1
)

:: 进入该目录执行cmake
cd /d "%SHIM_DIR%"
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
pause
