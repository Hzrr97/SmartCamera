@echo off
REM SmartCamera FFmpeg startup script (Windows)
REM Usage: start-ffmpeg.bat [camera_name] [camera_id] [rtsp_port]

set DEVICE_PATH=%~1
if "%DEVICE_PATH%"=="" set DEVICE_PATH=USB Camera
set CAMERA_ID=%~2
if "%CAMERA_ID%"=="" set CAMERA_ID=camera1
set RTSP_PORT=%~3
if "%RTSP_PORT%"=="" set RTSP_PORT=8554
set RESOLUTION=%~4
if "%RESOLUTION%"=="" set RESOLUTION=1920x1080
set FRAMERATE=%~5
if "%FRAMERATE%"=="" set FRAMERATE=25
set BITRATE=%~6
if "%BITRATE%"=="" set BITRATE=2000

echo Starting FFmpeg push stream...
echo   Device: %DEVICE_PATH%
echo   Camera: %CAMERA_ID%
echo   RTSP: rtsp://localhost:%RTSP_PORT%/live/%CAMERA_ID%
echo   Resolution: %RESOLUTION%
echo   Framerate: %FRAMERATE%
echo   Bitrate: %BITRATE%k

ffmpeg -f dshow -framerate %FRAMERATE% -video_size %RESOLUTION% -i video="%DEVICE_PATH%" -c:v libx264 -preset ultrafast -tune zerolatency -b:v %BITRATE%k -maxrate %BITRATE%k -bufsize %BITRATE%k -f rtsp rtsp://localhost:%RTSP_PORT%/live/%CAMERA_ID%

pause
