# 本地摄像头监控系统 - 测试用例文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档版本 | v1.0 |
| 创建日期 | 2026-05-10 |
| 适用范围 | 本地摄像头监控系统（SmartCamera） |

---

## 目录

1. [测试概述](#1-测试概述)
2. [单元测试用例](#2-单元测试用例)
3. [集成测试用例](#3-集成测试用例)
4. [端到端测试用例](#4-端到端测试用例)
5. [性能测试用例](#5-性能测试用例)
6. [安全测试用例](#6-安全测试用例)
7. [测试工具与脚本](#7-测试工具与脚本)

---

## 1. 测试概述

### 1.1 测试目标

验证本地摄像头监控系统满足需求文档定义的全部功能需求（F-01至F-19）和非功能需求（NF-01至NF-06）。

### 1.2 测试范围

| 测试类型 | 覆盖范围 |
|----------|----------|
| 单元测试 | Service层、Netty Handler、工具类 |
| 集成测试 | FFmpeg推流、MinIO存储、数据库操作、API接口 |
| 端到端测试 | 完整业务流程（采集→推流→接收→存储→回放） |
| 性能测试 | 延迟、吞吐量、内存泄漏、长时间稳定性 |
| 安全测试 | 认证授权、输入验证、SQL注入、文件上传安全 |

### 1.3 测试环境

```yaml
硬件环境:
  CPU: Intel i5/i7 或同等配置
  内存: 16GB+
  存储: SSD 100GB+
  摄像头: USB摄像头 (支持UVC标准)

软件环境:
  OS: Windows 11 / Ubuntu 22.04 LTS
  Java: OpenJDK 17
  Maven: 3.9+
  FFmpeg: 6.0+
  MySQL: 8.0
  MinIO: 2024.x
  Node.js: 18+ (前端测试)
```

---

## 2. 单元测试用例

### 2.1 FFmpeg 推流模块测试 (UT-FFMPEG-001 ~ 010)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| UT-FFMPEG-001 | FFmpeg命令构建 - Linux | 运行在Linux环境 | 调用FfmpegCommandBuilder.buildPushCommand() | 返回包含`-f v4l2 -i /dev/video0`的命令列表 | P0 |
| UT-FFMPEG-002 | FFmpeg命令构建 - Windows | 运行在Windows环境 | 调用FfmpegCommandBuilder.buildPushCommand() | 返回包含`-f dshow`的命令列表 | P0 |
| UT-FFMPEG-003 | FFmpeg命令参数验证 | 设置分辨率1920x1080、帧率25、码率2000kbps | 构建命令并检查参数 | 命令包含`-video_size 1920x1080 -framerate 25 -b:v 2000k` | P1 |
| UT-FFMPEG-004 | FFmpeg进程启动 | 摄像头设备可用 | 调用FfmpegManager.start() | 进程成功启动，Process对象非空 | P0 |
| UT-FFMPEG-005 | FFmpeg进程状态检查 | 进程已启动 | 调用FfmpegManager.isAlive() | 返回true | P0 |
| UT-FFMPEG-006 | FFmpeg进程优雅停止 | 进程运行中 | 调用FfmpegManager.stop() | 进程在5秒内退出，返回码0 | P0 |
| UT-FFMPEG-007 | FFmpeg进程强制停止 | 进程无响应 | 调用FfmpegManager.stop()后进程未退出 | 进程被强制销毁 | P1 |
| UT-FFMPEG-008 | FFmpeg自动重启 | 配置autoRetry=true, maxRetry=3 | 模拟进程异常退出 | 进程在retryDelaySeconds后自动重启，重启次数<=3 | P0 |
| UT-FFMPEG-009 | FFmpeg日志读取 | 进程运行中 | 启动日志读取线程 | 能读取到FFmpeg的输出日志 | P1 |
| UT-FFMPEG-010 | FFmpeg上下文获取 | 进程已启动 | 调用FfmpegManager.getContext() | 返回正确的FfmpegProcessContext，包含启动时间 | P1 |

### 2.2 Netty RTSP Server测试 (UT-NETTY-001 ~ 015)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| UT-NETTY-001 | RTSP服务器启动 | 端口8554未被占用 | 启动RtspServer | 服务器成功绑定8554端口 | P0 |
| UT-NETTY-002 | RTSP OPTIONS处理 | 服务器运行中 | 发送OPTIONS请求 | 返回200 OK，Public头包含OPTIONS,DESCRIBE,SETUP,PLAY,TEARDOWN | P0 |
| UT-NETTY-003 | RTSP DESCRIBE处理 | 服务器运行中 | 发送DESCRIBE请求，URI为rtsp://localhost:8554/live/camera1 | 返回200 OK，SDP包含H264、正确分辨率 | P0 |
| UT-NETTY-004 | RTSP DESCRIBE - 无效URI | 服务器运行中 | 发送DESCRIBE请求，URI格式错误 | 返回404 Not Found | P1 |
| UT-NETTY-005 | RTSP SETUP处理 | 已完成DESCRIBE | 发送SETUP请求，指定RTP/AVP传输 | 返回200 OK，包含Session头和server_port | P0 |
| UT-NETTY-006 | RTSP PLAY处理 | 已完成SETUP | 发送PLAY请求 | 返回200 OK，Session状态变为PLAYING | P0 |
| UT-NETTY-007 | RTSP TEARDOWN处理 | 正在PLAY状态 | 发送TEARDOWN请求 | 返回200 OK，Session被清理 | P0 |
| UT-NETTY-008 | RTP包解析 - Single NALU | 收到RTP包 | 解析payload type为Single NALU的包 | 正确提取H.264 NALU | P0 |
| UT-NETTY-009 | RTP包解析 - FU-A分片 | 收到FU-A分片序列 | 按序接收Start/Middle/End分片 | 重组出完整的NALU | P0 |
| UT-NETTY-010 | RTP包解析 - STAP-A聚合 | 收到STAP-A包 | 解析包含多个NALU的包 | 正确拆分出多个NALU | P1 |
| UT-NETTY-011 | Session管理 - 创建 | 收到SETUP请求 | 创建RtspSession | Session被加入sessions Map | P1 |
| UT-NETTY-012 | Session管理 - 清理 | 发送TEARDOWN或连接断开 | Session被移除 | Session从sessions Map中删除，端口释放 | P1 |
| UT-NETTY-013 | H264 NALU类型识别 | 有不同NALU类型的数据 | 调用H264Parser.getNaluType() | 正确识别SPS(7)、PPS(8)、IDR(5)、非IDR(1) | P0 |
| UT-NETTY-014 | 并发连接处理 | 服务器运行中 | 同时建立10个RTSP连接 | 所有连接正常处理，无异常 | P1 |
| UT-NETTY-015 | 无效RTSP方法 | 服务器运行中 | 发送PAUSE请求（未实现） | 返回405 Method Not Allowed | P2 |

### 2.3 视频分片与存储测试 (UT-STORAGE-001 ~ 012)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| UT-STORAGE-001 | TS复用器初始化 | 无 | 创建MpegTsMuxer | 对象创建成功，内部状态正确初始化 | P1 |
| UT-STORAGE-002 | H264 NALU封装为TS | 有H264 NALU数据 | 调用processNalu() | 返回有效的TS包数据(188字节倍数) | P0 |
| UT-STORAGE-003 | 分片管理 - 时间触发 | 配置segmentDurationMinutes=10 | 写入NALU持续超过10分钟 | 触发分片完成，启动新分片 | P0 |
| UT-STORAGE-004 | 分片管理 - 大小触发 | 配置segmentMaxSizeMb=100 | 写入数据超过100MB | 触发分片完成，启动新分片 | P1 |
| UT-STORAGE-005 | 分片命名生成 | cameraId=camera1, startTime=2026-05-10T14:30:00 | 生成分片文件名 | 格式为camera1/20260510/143000_xxx.ts | P1 |
| UT-STORAGE-006 | MinIO上传成功 | MinIO服务可用，bucket存在 | 调用uploadSegment() | 文件成功上传，返回的对象名正确 | P0 |
| UT-STORAGE-007 | MinIO上传失败重试 | 模拟MinIO网络故障 | 调用uploadSegment() | 按配置重试次数重试，最终失败记录错误日志 | P1 |
| UT-STORAGE-008 | 元数据入库 | 分片上传成功 | 保存VideoSegment到数据库 | 数据库记录正确，包含时间、路径、大小 | P0 |
| UT-STORAGE-009 | 预签名URL生成 | 文件已上传MinIO | 调用getSegmentUrl() | 返回有效的预签名URL，可访问 | P0 |
| UT-STORAGE-010 | 分片删除 | 分片存在 | 调用deleteSegment() | MinIO对象和数据库记录都被删除 | P1 |
| UT-STORAGE-011 | 过期分片查询 | 有已过期和未过期分片 | 调用findByExpiredAtBefore(now) | 只返回已过期的分片 | P1 |
| UT-STORAGE-012 | 分片计数 | 数据库有多个分片 | 调用countByCameraId() | 返回正确的分片数量 | P2 |

### 2.4 摄像头配置管理测试 (UT-CAMERA-001 ~ 010)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| UT-CAMERA-001 | 创建摄像头配置 | 数据库连接正常 | 调用save()新增配置 | 数据库新增记录，ID自动生成 | P0 |
| UT-CAMERA-002 | 查询摄像头配置 - ID | 配置已存在 | 调用findById() | 返回正确的配置对象 | P0 |
| UT-CAMERA-003 | 查询摄像头配置 - cameraId | 配置已存在 | 调用findByCameraId() | 返回正确的配置对象 | P0 |
| UT-CAMERA-004 | 查询摄像头配置 - 不存在 | 配置不存在 | 调用findById() | 返回Optional.empty() | P1 |
| UT-CAMERA-005 | 更新摄像头配置 | 配置已存在 | 修改字段后调用save() | 数据库记录更新，update_time变化 | P0 |
| UT-CAMERA-006 | 删除摄像头配置 | 配置已存在 | 调用deleteById() | 数据库记录被删除 | P0 |
| UT-CAMERA-007 | 按状态查询 | 多个摄像头不同状态 | 调用findByStatus("ONLINE") | 返回状态为ONLINE的摄像头列表 | P1 |
| UT-CAMERA-008 | 查询所有配置 | 有多个配置 | 调用findAll() | 返回所有配置列表 | P1 |
| UT-CAMERA-009 | cameraId唯一性 | 已存在cameraId=cam1 | 尝试插入相同cameraId | 抛出唯一约束异常 | P1 |
| UT-CAMERA-010 | 默认值设置 | 只设置必填字段 | 保存后查询 | 默认值正确设置(resolution=1920x1080等) | P2 |

### 2.5 回放服务测试 (UT-PLAYBACK-001 ~ 008)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| UT-PLAYBACK-001 | 按时间范围查询分片 | 数据库有多个时间段的视频分片 | 调用findByCameraIdAndStartTimeBetween() | 返回时间范围内按startTime升序的分片 | P0 |
| UT-PLAYBACK-002 | 按摄像头查询分片 | 多个摄像头有视频 | 指定cameraId查询 | 只返回该摄像头的分片 | P0 |
| UT-PLAYBACK-003 | 无匹配分片 | 时间范围内无视频 | 查询 | 返回空列表 | P1 |
| UT-PLAYBACK-004 | M3U8播放列表生成 | 有匹配的分片列表 | 调用getM3u8Playlist() | 返回有效M3U8格式，包含正确的EXTINF和URL | P0 |
| UT-PLAYBACK-005 | 分片合并调用 | 有多个TS分片 | 调用FFmpeg合并命令 | 生成有效的MP4文件 | P0 |
| UT-PLAYBACK-006 | 查询分片详细信息 | 分片存在 | 调用findById() | 返回包含完整元数据的对象 | P1 |
| UT-PLAYBACK-007 | 分片不存在 | 分片ID无效 | 调用findById() | 抛出异常或返回空Optional | P1 |
| UT-PLAYBACK-008 | 获取分片下载URL | 分片存在 | 调用getSegmentUrl() | 返回有效的下载URL | P1 |

---

## 3. 集成测试用例

### 3.1 FFmpeg与RTSP Server集成 (IT-FFMPEG-RTSP-001 ~ 005)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| IT-FFMPEG-RTSP-001 | FFmpeg与RTSP握手完整流程 | RTSP Server已启动 | 1. FFmpeg发送OPTIONS 2. 发送DESCRIBE 3. 发送SETUP 4. 发送PLAY | 每个步骤都返回200 OK，PLAY后开始接收RTP包 | P0 |
| IT-FFMPEG-RTSP-002 | RTSP推流数据接收 | 已完成PLAY握手 | FFmpeg持续推送RTP包 | Server正确接收并解析RTP包，提取NALU | P0 |
| IT-FFMPEG-RTSP-003 | RTSP会话关闭 | 正在推流 | 发送TEARDOWN或FFmpeg进程终止 | Session正确清理，资源释放 | P0 |
| IT-FFMPEG-RTSP-004 | 多路FFmpeg推流 | RTSP Server运行中 | 启动3个FFmpeg进程推送到不同URI | 每个流独立处理，数据不混淆 | P1 |
| IT-FFMPEG-RTSP-005 | FFmpeg断线重连 | 正在推流 | 模拟网络断开，FFmpeg自动重连 | Server接受新连接，继续处理 | P1 |

### 3.2 RTSP Server与分片存储集成 (IT-RTSP-STORAGE-001 ~ 005)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| IT-RTSP-STORAGE-001 | NALU到TS分片完整链路 | RTSP Server接收NALU | NALU经过MpegTsMuxer封装写入文件 | 生成可播放的TS文件 | P0 |
| IT-RTSP-STORAGE-002 | 分片自动切割 | 配置10分钟分片 | 持续接收NALU超过10分钟 | 旧分片上传MinIO并入库，新分片创建 | P0 |
| IT-RTSP-STORAGE-003 | 分片上传MinIO | TS分片文件已生成 | 触发上传流程 | MinIO中存在对象，数据库有记录 | P0 |
| IT-RTSP-STORAGE-004 | 上传失败本地缓存 | MinIO不可用 | 分片完成后尝试上传 | 本地保留文件，记录待上传状态 | P1 |
| IT-RTSP-STORAGE-005 | 上传重试机制 | 上传失败 | 等待重试间隔 | 自动重试上传直到成功或达到最大重试次数 | P1 |

### 3.3 数据库与MinIO一致性集成 (IT-DB-MINIO-001 ~ 004)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| IT-DB-MINIO-001 | 上传成功一致性 | 上传流程完成 | 检查数据库和MinIO | 数据库记录和MinIO对象都存在，信息一致 | P0 |
| IT-DB-MINIO-002 | 删除一致性 | 分片存在 | 调用deleteSegment() | 数据库记录和MinIO对象都被删除 | P0 |
| IT-DB-MINIO-003 | 数据库记录存在但MinIO对象不存在 | 模拟MinIO对象被手动删除 | 尝试获取分片URL | 处理异常，记录日志 | P1 |
| IT-DB-MINIO-004 | MinIO对象存在但数据库记录不存在 | 模拟数据库记录被删除 | 扫描MinIO bucket | 可发现并清理孤立对象 | P2 |

### 3.4 API接口集成测试 (IT-API-001 ~ 015)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| IT-API-001 | 获取摄像头列表 | 有多个摄像头配置 | GET /api/v1/cameras | 返回200和摄像头列表JSON | P0 |
| IT-API-002 | 创建摄像头 | 提供有效参数 | POST /api/v1/cameras | 返回201和创建的配置 | P0 |
| IT-API-003 | 创建摄像头 - 参数验证 | 缺少必填字段 | POST /api/v1/cameras | 返回400 Bad Request | P1 |
| IT-API-004 | 更新摄像头 | 摄像头存在 | PUT /api/v1/cameras/{id} | 返回200和更新后的配置 | P0 |
| IT-API-005 | 更新不存在摄像头 | ID不存在 | PUT /api/v1/cameras/{id} | 返回404 Not Found | P1 |
| IT-API-006 | 删除摄像头 | 摄像头存在 | DELETE /api/v1/cameras/{id} | 返回200，配置被删除 | P0 |
| IT-API-007 | 启动推流 | 摄像头存在且离线 | POST /api/v1/cameras/{id}/start | 返回200，FFmpeg进程启动 | P0 |
| IT-API-008 | 停止推流 | 摄像头正在推流 | POST /api/v1/cameras/{id}/stop | 返回200，FFmpeg进程停止 | P0 |
| IT-API-009 | 获取摄像头状态 | 摄像头存在 | GET /api/v1/cameras/{id}/status | 返回200和状态信息 | P0 |
| IT-API-010 | 查询回放分片 | 有时间范围内的视频 | GET /api/v1/playback/segments?cameraId=xxx&startTime=xxx&endTime=xxx | 返回200和分片列表 | P0 |
| IT-API-011 | 获取合并视频 | 有匹配分片 | GET /api/v1/playback/merge | 返回200和MP4下载URL | P0 |
| IT-API-012 | 获取M3U8播放列表 | 有匹配分片 | GET /api/v1/playback/playlist | 返回200和M3U8内容 | P0 |
| IT-API-013 | 获取系统状态 | 系统运行中 | GET /api/v1/system/status | 返回200和系统统计信息 | P0 |
| IT-API-014 | 清理过期视频 | 有过期视频 | DELETE /api/v1/storage/expired | 返回200，过期视频被清理 | P0 |
| IT-API-015 | HTTP-FLV实时流 | 摄像头正在推流 | GET /api/v1/streams/{cameraId}/live.flv | 返回200和FLV流 | P0 |

### 3.5 WebSocket实时流集成 (IT-WS-001 ~ 004)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| IT-WS-001 | WebSocket连接建立 | 摄像头正在推流 | 客户端连接/ws/video/live/{cameraId} | 连接成功，101 Switching Protocols | P0 |
| IT-WS-002 | WebSocket数据接收 | WebSocket已连接 | 等待数秒 | 客户端收到二进制视频帧数据 | P0 |
| IT-WS-003 | WebSocket多客户端 | 摄像头正在推流 | 3个客户端同时连接 | 每个客户端都能收到数据 | P1 |
| IT-WS-004 | WebSocket断开清理 | WebSocket连接中 | 客户端主动断开 | 服务端清理对应session，不影响其他客户端 | P1 |

---

## 4. 端到端测试用例

### 4.1 完整业务流程测试 (E2E-001 ~ 005)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| E2E-001 | 摄像头采集到存储完整链路 | 系统完全启动，摄像头可用 | 1. 添加摄像头配置 2. 启动推流 3. 等待10分钟 4. 检查MinIO和数据库 | MinIO中有TS文件，数据库有对应记录 | P0 |
| E2E-002 | 实时预览完整流程 | 摄像头正在推流 | 1. 前端打开监控页面 2. 播放FLV流 | 浏览器显示实时画面，延迟<3秒 | P0 |
| E2E-003 | 历史回放完整流程 | 已有录制好的视频分片 | 1. 前端打开回放页面 2. 选择时间范围 3. 点击播放 | 浏览器播放历史视频 | P0 |
| E2E-004 | 摄像头生命周期管理 | 系统运行中 | 1. 创建摄像头 2. 启动推流 3. 停止推流 4. 删除摄像头 | 每个操作都成功，资源正确释放 | P0 |
| E2E-005 | 系统重启恢复 | 有正在推流的摄像头 | 1. 重启Spring Boot应用 2. 检查之前配置 | 配置保留，可手动恢复推流 | P1 |

### 4.2 异常场景测试 (E2E-ERR-001 ~ 008)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| E2E-ERR-001 | FFmpeg进程崩溃 | 正在推流 | kill FFmpeg进程 | 系统在30秒内自动重启FFmpeg | P0 |
| E2E-ERR-002 | MySQL连接断开 | 系统运行中 | 停止MySQL服务 | 系统记录错误，连接恢复后正常工作 | P1 |
| E2E-ERR-003 | MinIO连接断开 | 系统运行中 | 停止MinIO服务 | 系统本地缓存，连接恢复后补传 | P1 |
| E2E-ERR-004 | 摄像头被拔出 | 正在推流 | 物理断开USB摄像头 | FFmpeg进程退出，系统标记摄像头离线 | P1 |
| E2E-ERR-005 | 磁盘空间不足 | 录制中 | 模拟磁盘满 | 系统记录错误，停止写入 | P1 |
| E2E-ERR-006 | 内存不足 | 系统运行中 | 持续运行并监控内存 | 无内存泄漏，GC正常 | P1 |
| E2E-ERR-007 | 网络抖动 | RTSP推流中 | 模拟网络延迟/丢包 | 系统保持连接，数据不损坏 | P2 |
| E2E-ERR-008 | 并发操作冲突 | 系统运行中 | 同时进行多个操作 | 无数据竞争，操作正确执行 | P2 |

### 4.3 多摄像头场景测试 (E2E-MULTI-001 ~ 003)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| E2E-MULTI-001 | 多摄像头同时推流 | 有3个USB摄像头 | 同时启动3个摄像头推流 | 每个流独立录制，数据不混淆 | P1 |
| E2E-MULTI-002 | 多摄像头实时预览 | 3个摄像头推流中 | 同时打开3个监控页面 | 每个页面显示对应摄像头画面 | P1 |
| E2E-MULTI-003 | 多摄像头存储隔离 | 3个摄像头有录像 | 查询回放时切换摄像头 | 每个摄像头只显示自己的录像 | P1 |

---

## 5. 性能测试用例

### 5.1 延迟测试 (PERF-LATENCY-001 ~ 004)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| PERF-LATENCY-001 | 端到端延迟测试 | 摄像头→RTSP→FLV→前端完整链路 | 在视频画面显示时间戳，对比显示时间与当前时间 | 延迟 < 3秒 (NF-02) | P0 |
| PERF-LATENCY-002 | RTSP握手延迟 | RTSP Server运行中 | 测量OPTIONS→PLAY的时间 | < 100ms | P1 |
| PERF-LATENCY-003 | API响应延迟 | 系统运行中 | 测量各API平均响应时间 | 简单查询 < 100ms，复杂查询 < 500ms | P1 |
| PERF-LATENCY-004 | WebSocket帧延迟 | WebSocket连接中 | 测量帧从接收到推送的时间 | < 50ms | P1 |

### 5.2 吞吐量测试 (PERF-THROUGHPUT-001 ~ 003)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| PERF-THROUGHPUT-001 | 单路1080P流处理 | 1080P@25fps摄像头 | 持续接收处理1小时 | 不丢帧，CPU使用<50% (NF-01) | P0 |
| PERF-THROUGHPUT-002 | 并发WebSocket客户端 | 单路推流中 | 逐步增加WebSocket客户端数量，直到延迟明显增加 | 支持至少10个并发客户端 | P1 |
| PERF-THROUGHPUT-003 | 数据库查询性能 | 数据库有10万条分片记录 | 执行时间范围查询 | 查询时间 < 1秒 | P1 |

### 5.3 存储效率测试 (PERF-STORAGE-001 ~ 003)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| PERF-STORAGE-001 | 1080P每小时存储大小 | 1080P@25fps, 2000kbps码率 | 录制1小时，统计TS文件大小 | < 2GB (NF-04) | P0 |
| PERF-STORAGE-002 | 分片上传性能 | MinIO正常运行 | 测量100MB文件上传时间 | < 30秒 | P1 |
| PERF-STORAGE-003 | 存储空间监控准确性 | 已知大小的视频 | 对比系统统计与实际存储 | 误差 < 5% | P2 |

### 5.4 稳定性测试 (PERF-STABLE-001 ~ 003)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| PERF-STABLE-001 | 24小时连续运行 | 系统完全配置 | 持续运行24小时，监控各项指标 | 无崩溃，内存增长<10% | P0 |
| PERF-STABLE-002 | 内存泄漏检测 | 持续推流中 | 每2小时dump堆内存分析 | 无内存泄漏 | P0 |
| PERF-STABLE-003 | FFmpeg自动恢复 | 配置autoRetry | 每10分钟kill FFmpeg，持续1小时 | FFmpeg每次都成功重启 | P0 |

### 5.5 负载测试 (PERF-LOAD-001 ~ 002)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| PERF-LOAD-001 | API高并发 | 系统运行中 | 使用JMeter模拟100并发用户访问API | 响应时间增加<50%，无错误 | P1 |
| PERF-LOAD-002 | 回放并发 | 有历史视频 | 10个用户同时回放不同时间段 | 播放流畅，无卡顿 | P1 |

---

## 6. 安全测试用例

### 6.1 输入验证测试 (SEC-INPUT-001 ~ 005)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| SEC-INPUT-001 | SQL注入防护 | 系统运行中 | 在API参数中注入SQL语句 | 请求被拒绝或无害化，无SQL执行 | P0 |
| SEC-INPUT-002 | XSS防护 | 系统运行中 | 在输入字段中插入XSS脚本 | 脚本被转义，不执行 | P1 |
| SEC-INPUT-003 | 参数长度限制 | 系统运行中 | 发送超长字符串参数 | 返回400错误，系统正常 | P1 |
| SEC-INPUT-004 | 特殊字符处理 | 系统运行中 | 在参数中使用特殊字符 | 正确处理，不报错 | P1 |
| SEC-INPUT-005 | 空值/Null处理 | 系统运行中 | 发送空值或省略必填字段 | 返回验证错误 | P1 |

### 6.2 认证授权测试 (SEC-AUTH-001 ~ 004)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| SEC-AUTH-001 | MinIO认证 | MinIO启用认证 | 使用错误凭证访问 | 访问被拒绝 | P0 |
| SEC-AUTH-002 | MinIO凭证正确性 | MinIO启用认证 | 使用正确凭证访问 | 访问成功 | P0 |
| SEC-AUTH-003 | 预签名URL时效性 | 文件存在 | 使用过期预签名URL访问 | 访问被拒绝 | P1 |
| SEC-AUTH-004 | RTSP可选认证 | RTSP启用认证 | 未认证尝试推流 | 根据配置接受或拒绝 | P2 |

### 6.3 文件安全测试 (SEC-FILE-001 ~ 003)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| SEC-FILE-001 | 文件类型验证 | 系统运行中 | 尝试上传非TS文件 | 上传被拒绝 | P1 |
| SEC-FILE-002 | 文件名注入 | 系统运行中 | 使用包含路径遍历的文件名 | 路径被规范化，无法跳出目录 | P1 |
| SEC-FILE-003 | 文件大小限制 | 系统运行中 | 上传超大文件 | 上传被拒绝或限制 | P1 |

### 6.4 网络安全测试 (SEC-NET-001 ~ 003)

| 用例编号 | 测试项 | 前置条件 | 测试步骤 | 预期结果 | 优先级 |
|----------|--------|----------|----------|----------|--------|
| SEC-NET-001 | 端口扫描防护 | 系统运行中 | 扫描开放端口 | 只开放必要端口(8080, 8554, WebSocket) | P1 |
| SEC-NET-002 | DDoS防护 | 系统运行中 | 短时间内大量连接请求 | 系统保持可用，可配置限流 | P2 |
| SEC-NET-003 | 数据加密传输 | 系统配置 | 检查数据传输 | 敏感配置使用HTTPS/WSS | P2 |

---

## 7. 测试工具与脚本

### 7.1 单元测试工具

```xml
<!-- pom.xml 测试依赖 -->
<dependencies>
    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- Mockito -->
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- TestContainers (集成测试) -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <version>1.19.7</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mysql</artifactId>
        <version>1.19.7</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 7.2 性能测试脚本

```java
// JMH 基准测试示例 - RTSP Handler性能
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Thread)
public class RtspHandlerBenchmark {
    
    private RtspServerHandler handler;
    
    @Setup
    public void setup() {
        handler = new RtspServerHandler();
    }
    
    @Benchmark
    public void testOptionsRequest() {
        // 测试OPTIONS请求处理性能
    }
}
```

### 7.3 API测试脚本 (JMeter)

```jmx
<!-- 测试计划结构 -->
Test Plan
├── Thread Group (100 threads, 10 loops)
│   ├── HTTP Request (GET /api/v1/cameras)
│   ├── HTTP Request (POST /api/v1/cameras)
│   ├── HTTP Request (GET /api/v1/playback/segments)
│   └── View Results Tree
├── Summary Report
└── Graph Results
```

### 7.4 端到端测试脚本

```bash
#!/bin/bash
# e2e-test.sh - 端到端测试脚本

# 1. 启动基础设施
docker-compose up -d minio mysql

# 2. 等待服务就绪
sleep 10

# 3. 创建MinIO bucket
mc mb local/streams || true

# 4. 启动应用
mvn spring-boot:run &
APP_PID=$!

# 5. 等待应用启动
sleep 30

# 6. 执行测试用例
echo "执行E2E测试..."

# 创建摄像头
curl -X POST http://localhost:8080/api/v1/cameras \
  -H "Content-Type: application/json" \
  -d '{"cameraId":"test-cam-1","cameraName":"Test Camera","devicePath":"/dev/video0"}'

# 启动推流
curl -X POST http://localhost:8080/api/v1/cameras/1/start

# 等待录制
echo "等待录制60秒..."
sleep 60

# 查询回放分片
curl "http://localhost:8080/api/v1/playback/segments?cameraId=test-cam-1&startTime=2026-01-01T00:00:00&endTime=2026-12-31T23:59:59"

# 停止推流
curl -X POST http://localhost:8080/api/v1/cameras/1/stop

# 清理
kill $APP_PID
docker-compose down

echo "E2E测试完成"
```

### 7.5 测试报告模板

| 测试轮次 | 日期 | 测试范围 | 通过数 | 失败数 | 阻塞数 | 通过率 | 状态 |
|----------|------|----------|--------|--------|--------|--------|------|
| 第1轮 | 2026-05-10 | 单元测试 | 50 | 0 | 0 | 100% | 通过 |
| 第1轮 | 2026-05-10 | 集成测试 | 30 | 2 | 1 | 90% | 有条件通过 |
| 第1轮 | 2026-05-10 | E2E测试 | 10 | 0 | 0 | 100% | 通过 |

---

## 附录

### A. 测试优先级定义

| 优先级 | 定义 | 说明 |
|--------|------|------|
| P0 | 阻塞性 | 必须100%通过，否则系统无法上线 |
| P1 | 高优先级 | 重要功能，应尽可能通过 |
| P2 | 中优先级 | 次要功能，可在后续迭代修复 |

### B. 需求追溯矩阵

| 需求编号 | 需求描述 | 测试用例覆盖 |
|----------|----------|--------------|
| F-01 | 摄像头接入 | UT-FFMPEG-001,002; IT-FFMPEG-RTSP-001 |
| F-02 | RTSP推流 | UT-FFMPEG-004~010; IT-FFMPEG-RTSP-001~005 |
| F-03 | 流参数配置 | UT-FFMPEG-003; UT-CAMERA-009 |
| F-05 | RTSP接收 | UT-NETTY-001~007 |
| F-06 | 流媒体处理 | UT-NETTY-008~014 |
| F-07 | 实时预览 | IT-WS-001~004; E2E-002 |
| F-09 | 分片存储 | UT-STORAGE-001~005; IT-RTSP-STORAGE-001~005 |
| F-10 | MinIO上传 | UT-STORAGE-006~011 |
| F-11 | 索引记录 | UT-STORAGE-008; UT-PLAYBACK-001~003 |
| F-12 | 存储策略 | IT-API-014 |
| F-13 | 时间检索 | UT-PLAYBACK-001; IT-API-010 |
| F-14 | 摄像头筛选 | UT-PLAYBACK-002 |
| F-15 | 视频回放 | E2E-003; IT-API-011,012 |
| F-17 | 状态监控 | IT-API-009,013 |
| NF-01 | 1080P性能 | PERF-THROUGHPUT-001 |
| NF-02 | 延迟<3秒 | PERF-LATENCY-001 |
| NF-03 | 自动重连 | E2E-ERR-001; PERF-STABLE-003 |
| NF-04 | 存储效率 | PERF-STORAGE-001 |

---

*文档结束*
