#!/bin/bash
# SmartCamera FFmpeg startup script (Linux)
# Usage: ./start-ffmpeg.sh [device_path] [camera_id] [rtsp_port]

DEVICE_PATH=${1:-/dev/video0}
CAMERA_ID=${2:-camera1}
RTSP_PORT=${3:-8554}
RESOLUTION=${4:-1920x1080}
FRAMERATE=${5:-25}
BITRATE=${6:-2000}

echo "Starting FFmpeg push stream..."
echo "  Device: $DEVICE_PATH"
echo "  Camera: $CAMERA_ID"
echo "  RTSP: rtsp://localhost:$RTSP_PORT/live/$CAMERA_ID"
echo "  Resolution: $RESOLUTION"
echo "  Framerate: $FRAMERATE"
echo "  Bitrate: ${BITRATE}k"

ffmpeg -f v4l2 \
  -framerate "$FRAMERATE" \
  -video_size "$RESOLUTION" \
  -i "$DEVICE_PATH" \
  -c:v libx264 \
  -preset ultrafast \
  -tune zerolatency \
  -b:v "${BITRATE}k" \
  -maxrate "${BITRATE}k" \
  -bufsize "$((BITRATE * 2))k" \
  -f rtsp "rtsp://localhost:$RTSP_PORT/live/$CAMERA_ID"
