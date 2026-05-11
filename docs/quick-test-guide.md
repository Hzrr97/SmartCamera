# 本地摄像头监控系统 - 快速测试指南

本文档提供最简单的测试流程，帮助您快速验证系统核心功能。

---

## 目录

1. [环境准备](#1-环境准备)
2. [启动系统](#2-启动系统)
3. [连接摄像头](#3-连接摄像头)
4. [查看实时画面](#4-查看实时画面)
5. [查看存储录像](#5-查看存储录像)
6. [常见问题排查](#6-常见问题排查)

---

## 1. 环境准备

### 1.1 必需软件

| 软件 | 版本 | 用途 | 下载地址 |
|------|------|------|----------|
| FFmpeg | 6.0+ | 视频采集与推流 | https://ffmpeg.org/download.html |
| Java | 17+ | 运行后端服务 | https://adoptium.net/ |
| Maven | 3.9+ | 构建项目 | https://maven.apache.org/ |
| VLC播放器 | 最新版 | 测试RTSP流 | https://www.videolan.org/ |

### 1.2 检查FFmpeg安装

```bash
# Windows
cmd> ffmpeg -version
ffmpeg version 6.0-full_build-www.gyan.dev

# Linux/Unix
$ ffmpeg -version
ffmpeg version 6.0
```

### 1.3 检查摄像头设备

**Windows:**
```cmd
# 列出视频设备
ffmpeg -list_devices true -f dshow -i dummy
```
输出示例：
```
[dshow @ 000001] DirectShow video devices (some may be both video and audio devices)
[dshow @ 000001]  "USB Camera"        <-- 您的摄像头名称
[dshow @ 000001]     Alternative name "@device_pnp_\\..."
```

**Linux:**
```bash
# 列出V4L2设备
ls /dev/video*
# 输出: /dev/video0 /dev/video1

# 查看设备信息
v4l2-ctl --device=/dev/video0 --all
```

---

## 2. 启动系统

### 2.1 启动基础设施

```bash
# 进入项目目录
cd F:/学习/SmartCamera/backend/docker

# 启动MinIO和MySQL
docker-compose up -d minio mysql

# 等待10秒让服务完全启动
```

### 2.2 初始化MinIO

```bash
# 安装mc命令行工具 (如果还没有)
# Windows: https://min.io/docs/minio/windows/operations/installation.html
# Linux: wget https://dl.min.io/client/mc/release/linux-amd64/mc

# 配置MinIO别名
mc alias set local http://localhost:9000 minioadmin minioadmin

# 创建存储bucket
mc mb local/streams

# 验证
mc ls local
# 输出: [2026-05-10]  bucket: streams/
```

### 2.3 启动后端服务

```bash
# 进入后端目录
cd F:/学习/SmartCamera/backend

# 编译并运行
mvn clean compile
mvn spring-boot:run

# 看到以下日志表示启动成功:
# [INFO] Started SmartCameraApplication in x.x seconds
# [INFO] RTSP server started successfully on port 8554
```

### 2.4 验证服务状态

```bash
# 测试API是否可用
curl http://localhost:8080/api/v1/system/status

# 应该返回系统状态信息
```

---

## 3. 连接摄像头

### 3.1 方式一：通过API添加摄像头（推荐）

```bash
# 1. 创建摄像头配置
curl -X POST http://localhost:8080/api/v1/cameras \
  -H "Content-Type: application/json" \
  -d '{
    "cameraId": "cam-001",
    "cameraName": "测试摄像头",
    "devicePath": "/dev/video0",
    "resolution": "1920x1080",
    "framerate": 25,
    "bitrateKbps": 2000,
    "rtspPort": 8554,
    "enabled": true
  }'

# Windows用户注意: devicePath应为 "video=USB Camera" 格式

# 2. 启动推流
curl -X POST http://localhost:8080/api/v1/cameras/1/start

# 3. 检查状态
curl http://localhost:8080/api/v1/cameras/1/status
```

### 3.2 方式二：手动启动FFmpeg（调试使用）

**Windows:**
```cmd
ffmpeg -f dshow -framerate 25 -video_size 1920x1080 -i "video=USB Camera" ^
  -c:v libx264 -preset ultrafast -tune zerolatency ^
  -b:v 2000k -maxrate 2000k -bufsize 4000k ^
  -f rtsp rtsp://localhost:8554/live/cam-001
```

**Linux:**
```bash
ffmpeg -f v4l2 -framerate 25 -video_size 1920x1080 -i /dev/video0 \
  -c:v libx264 -preset ultrafast -tune zerolatency \
  -b:v 2000k -maxrate 2000k -bufsize 4000k \
  -f rtsp rtsp://localhost:8554/live/cam-001
```

### 3.3 验证推流成功

**查看FFmpeg输出：**
- 看到 `frame=  123 fps= 25 q=-1.0 Lsize=     456kB` 类似信息表示推流正常
- 没有报错信息

**查看后端日志：**
```
[INFO] RTSP DESCRIBE received for rtsp://localhost:8554/live/cam-001
[INFO] RTSP PLAY started for session xxx camera cam-001
[INFO] Started new segment for camera cam-001
```

**使用VLC验证：**
1. 打开VLC播放器
2. 媒体 → 打开网络串流
3. 输入: `rtsp://localhost:8554/live/cam-001`
4. 点击播放，应能看到摄像头画面

---

## 4. 查看实时画面

### 4.1 HTTP-FLV方式（推荐用于Web）

**测试命令行:**
```bash
# 使用ffplay测试FLV流
ffplay http://localhost:8080/api/v1/streams/cam-001/live.flv

# 或者使用curl查看流是否正常返回
curl -I http://localhost:8080/api/v1/streams/cam-001/live.flv
```

**浏览器播放:**
1. 创建一个简单的HTML文件:

```html
<!DOCTYPE html>
<html>
<head>
  <title>实时监控</title>
  <script src="https://cdn.jsdelivr.net/npm/flv.js@1.6.2/dist/flv.min.js"></script>
</head>
<body>
  <h1>摄像头实时画面</h1>
  <video id="video" width="640" height="360" controls autoplay muted></video>
  
  <script>
    if (flvjs.isSupported()) {
      var video = document.getElementById('video');
      var flvPlayer = flvjs.createPlayer({
        type: 'flv',
        url: 'http://localhost:8080/api/v1/streams/cam-001/live.flv'
      });
      flvPlayer.attachMediaElement(video);
      flvPlayer.load();
      flvPlayer.play();
    }
  </script>
</body>
</html>
```

2. 用浏览器打开该HTML文件
3. 应能看到实时画面（延迟约1-3秒）

### 4.2 检查流状态

```bash
# 获取流状态
curl http://localhost:8080/api/v1/cameras/1/status

# 预期输出:
{
  "cameraId": "cam-001",
  "cameraName": "测试摄像头",
  "status": "ONLINE",
  "isStreaming": true,
  "uptimeSeconds": 120
}
```

---

## 5. 查看存储录像

### 5.1 等待分片生成

系统默认每10分钟生成一个视频分片，也可以在 `application.yml` 中配置:
```yaml
camera:
  stream:
    segment-duration-minutes: 10  # 改为1方便测试
```

**检查分片是否生成:**

```bash
# 方法1: 查看后端日志
# 看到 "Segment uploaded to MinIO" 表示上传成功

# 方法2: 查询数据库
curl "http://localhost:8080/api/v1/playback/segments?cameraId=cam-001&startTime=2026-01-01T00:00:00&endTime=2026-12-31T23:59:59"

# 方法3: 查看MinIO
mc ls local/streams/cam-001/
```

### 5.2 查询录像列表

```bash
# 查询今天的录像
curl "http://localhost:8080/api/v1/playback/segments?cameraId=cam-001&startTime=$(date -d 'today 00:00' +%Y-%m-%dT%H:%M:%S)&endTime=$(date -d 'tomorrow 00:00' +%Y-%m-%dT%H:%M:%S)"
```

预期输出:
```json
[
  {
    "id": 1,
    "cameraId": "cam-001",
    "filePath": "cam-001/20260510/143000_001.ts",
    "startTime": "2026-05-10T14:30:00",
    "endTime": "2026-05-10T14:40:00",
    "durationMs": 600000,
    "fileSize": 150000000
  }
]
```

### 5.3 下载单个分片

```bash
# 方法1: 通过API获取下载URL
curl "http://localhost:8080/api/v1/playback/download/1"

# 方法2: 直接从MinIO下载
mc cp local/streams/cam-001/20260510/143000_001.ts ./recording.ts

# 使用VLC播放下载的文件
vlc ./recording.ts
```

### 5.4 获取M3U8播放列表

```bash
# 获取播放列表
curl "http://localhost:8080/api/v1/playback/playlist?cameraId=cam-001&startTime=2026-05-10T00:00:00&endTime=2026-05-10T23:59:59"

# 预期输出:
#EXTM3U
#EXT-X-VERSION:3
#EXTINF:600.0,
http://localhost:9000/streams/cam-001/20260510/143000_001.ts?X-Amz-Algorithm=...
#EXT-X-ENDLIST
```

**用VLC播放M3U8:**
1. 将M3U8内容保存为 `playlist.m3u8`
2. VLC打开该文件即可顺序播放

### 5.5 合并回放（服务端合并）

```bash
# 请求合并后的视频
curl "http://localhost:8080/api/v1/playback/merge?cameraId=cam-001&startTime=2026-05-10T14:00:00&endTime=2026-05-10T15:00:00"

# 返回合并后的MP4下载URL
# 注意: 此功能需要FFmpeg合并支持
```

---

## 6. 常见问题排查

### 6.1 FFmpeg找不到摄像头

**现象:**
```
[video4linux2] Cannot open video device /dev/video0: No such file or directory
```

**解决:**
```bash
# Linux: 检查设备权限
sudo chmod 666 /dev/video0

# 或使用sudo运行FFmpeg
sudo ffmpeg -f v4l2 ...

# Windows: 确认设备名称正确
ffmpeg -list_devices true -f dshow -i dummy
# 使用引号包裹含空格的名称
ffmpeg -f dshow -i "video=USB Camera" ...
```

### 6.2 RTSP连接失败

**现象:**
```
Connection refused: localhost/127.0.0.1:8554
```

**解决:**
```bash
# 1. 检查后端是否启动
curl http://localhost:8080/api/v1/system/status

# 2. 检查RTSP端口是否被占用
netstat -ano | findstr :8554  # Windows
lsof -i :8554                  # Linux/Mac

# 3. 检查防火墙
# Windows: 允许Java通过防火墙
# Linux: sudo ufw allow 8554/tcp
```

### 6.3 VLC播放卡顿

**可能原因和解决:**
1. **码率过高**: 降低FFmpeg的`-b:v`参数，如改为1000k
2. **CPU不足**: 使用更快的preset `-preset superfast`
3. **网络延迟**: 检查网络连接，使用有线连接
4. **解码问题**: VLC工具 → 首选项 → 输入/编解码器 → 硬件加速解码 → 禁用

### 6.4 MinIO连接失败

**现象:**
```
Error: connect ECONNREFUSED 127.0.0.1:9000
```

**解决:**
```bash
# 1. 检查Docker容器状态
docker-compose ps

# 2. 重启MinIO
docker-compose restart minio

# 3. 检查端口映射
docker-compose logs minio
```

### 6.5 数据库连接失败

**现象:**
```
Communications link failure
```

**解决:**
```bash
# 1. 检查MySQL容器
docker-compose ps

# 2. 查看日志
docker-compose logs mysql

# 3. 手动连接测试
mysql -h localhost -P 3306 -u root -p
```

### 6.6 没有录像生成

**检查清单:**
1. [ ] FFmpeg是否正常运行？（查看FFmpeg输出帧数）
2. [ ] RTSP握手是否成功？（查看后端日志）
3. [ ] 是否等待足够时间？（默认10分钟一个分片）
4. [ ] MinIO是否正常？（`mc ls local`）
5. [ ] 数据库是否正常？（能否查询到记录）

**快速诊断:**
```bash
# 一键检查所有组件状态
echo "=== 后端API ==="
curl -s http://localhost:8080/api/v1/system/status | head -5

echo "=== MinIO ==="
mc ls local/streams/ 2>/dev/null || echo "MinIO不可用"

echo "=== 数据库 ==="
mysql -h localhost -P 3306 -u root -p123456 -e "SELECT COUNT(*) FROM smart_camera.video_segment;" 2>/dev/null || echo "数据库不可用"

echo "=== FFmpeg进程 ==="
ps aux | grep ffmpeg | grep -v grep  # Linux
tasklist | findstr ffmpeg            # Windows
```

---

## 7. 常用命令速查

### 7.1 摄像头管理

```bash
# 列出所有摄像头
curl http://localhost:8080/api/v1/cameras

# 添加摄像头
curl -X POST http://localhost:8080/api/v1/cameras \
  -H "Content-Type: application/json" \
  -d '{"cameraId":"cam-001","cameraName":"测试摄像头","devicePath":"/dev/video0"}'

# 启动推流
curl -X POST http://localhost:8080/api/v1/cameras/1/start

# 停止推流
curl -X POST http://localhost:8080/api/v1/cameras/1/stop

# 删除摄像头
curl -X DELETE http://localhost:8080/api/v1/cameras/1
```

### 7.2 回放查询

```bash
# 查询分片列表
curl "http://localhost:8080/api/v1/playback/segments?cameraId=cam-001&startTime=2026-05-10T00:00:00&endTime=2026-05-10T23:59:59"

# 获取M3U8播放列表
curl "http://localhost:8080/api/v1/playback/playlist?cameraId=cam-001&startTime=2026-05-10T00:00:00&endTime=2026-05-10T23:59:59"

# 清理过期视频
curl -X DELETE http://localhost:8080/api/v1/storage/expired
```

### 7.3 Docker操作

```bash
# 启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 重启服务
docker-compose restart

# 停止并清理
docker-compose down -v

# 进入MySQL容器
docker-compose exec mysql mysql -u root -p

# 进入MinIO容器
docker-compose exec minio sh
```

---

## 附录: 测试检查表

开始测试前，请确认:

- [ ] FFmpeg已安装并能正常运行
- [ ] 摄像头已连接且被系统识别
- [ ] Docker已安装并运行
- [ ] Java 17+ 已安装
- [ ] 端口 8080, 8554, 3306, 9000 未被占用
- [ ] 至少10GB可用磁盘空间

测试过程中检查:

- [ ] 后端启动无错误日志
- [ ] FFmpeg推流帧数正常增加
- [ ] VLC能播放RTSP流
- [ ] 数据库有分片记录生成
- [ ] MinIO有TS文件上传
- [ ] HTTP-FLV流能正常播放

---

*文档结束*
