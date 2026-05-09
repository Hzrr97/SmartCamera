# 本地摄像头监控系统 - 技术方案文档

## 1. 系统架构

### 1.1 总体架构图

```
┌─────────────┐     FFmpeg推流(RTSP)     ┌──────────────────────────────┐
│  USB 摄像头  │ ──────────────────────► │        Spring Boot 应用      │
│  (V4L2/DS)  │                          │                              │
└─────────────┘                          │  ┌────────────────────────┐  │
                                         │  │   FFmpeg 推流脚本       │  │
                                         │  │  (摄像头→RTSP→Netty)   │  │
                                         │  └────────┬───────────────┘  │
                                         │           │                  │
                                         │  ┌────────▼───────────────┐  │
                                         │  │   Netty RTSP Server    │  │
                                         │  │  端口: 8554            │  │
                                         │  │  协议: RTSP/RTP        │  │
                                         │  └────────┬───────────────┘  │
                                         │           │                  │
                            ┌────────────┴────────────┐                 │
                            │                         │                 │
                   ┌────────▼────────┐      ┌─────────▼──────────┐     │
                   │  实时预览分发    │      │  视频分片与存储     │     │
                   │  (WebSocket)    │      │  (TS切片→MinIO)     │     │
                   └────────┬────────┘      └─────────┬──────────┘     │
                            │                         │                 │
                            │                 ┌───────▼──────────┐     │
                            │                 │   MinIO 存储      │     │
                            │                 │  Bucket: streams   │     │
                            │                 └──────────────────┘     │
                            │                                          │
                            │                 ┌──────────────────┐     │
                            │                 │   MySQL/Postgres  │     │
                            │                 │  (视频索引元数据)  │     │
                            │                 └──────────────────┘     │
                            └──────────────────────────────────────────┘
                                         │
                                         ▼
                            ┌─────────────────────────┐
                            │      前端 (Vue/React)    │
                            │  实时监控 + 历史回放     │
                            └─────────────────────────┘
```

### 1.2 架构分层

```
┌───────────────────────────────────────────────────┐
│  表现层 (Presentation Layer)                       │
│  ├── REST API (Spring MVC)                         │
│  ├── WebSocket (实时视频流)                         │
│  └── 静态资源 (前端页面)                             │
├───────────────────────────────────────────────────┤
│  业务层 (Service Layer)                            │
│  ├── CameraService     - 摄像头管理                  │
│  ├── StreamService     - 流管理                      │
│  ├── StorageService    - 视频存储管理                 │
│  ├── PlaybackService   - 回放检索                     │
│  └── RecordScheduler   - 录制调度                     │
├───────────────────────────────────────────────────┤
│  通信层 (Transport Layer)                           │
│  ├── Netty RTSP Server  - 接收RTSP推流               │
│  ├── Netty RTP Handler  - 解析RTP包                 │
│  └── WebSocket Handler  - 实时流分发                 │
├───────────────────────────────────────────────────┤
│  数据层 (Data Layer)                               │
│  ├── MinIO Client      - 对象存储                    │
│  └── JPA/MyBatis       - 元数据持久化                │
└───────────────────────────────────────────────────┘
```

## 2. 核心模块设计

### 2.1 FFmpeg 推流模块

#### 2.1.1 推流命令

**Linux (V4L2):**
```bash
ffmpeg -f v4l2 -framerate 25 -video_size 1920x1080 -i /dev/video0 \
  -c:v libx264 -preset ultrafast -tune zerolatency \
  -b:v 2000k -maxrate 2000k -bufsize 4000k \
  -f rtsp rtsp://localhost:8554/live/camera1
```

**Windows (DirectShow):**
```bash
ffmpeg -f dshow -framerate 25 -video_size 1920x1080 -i video="USB Camera" ^
  -c:v libx264 -preset ultrafast -tune zerolatency ^
  -b:v 2000k -maxrate 2000k -bufsize 4000k ^
  -f rtsp rtsp://localhost:8554/live/camera1
```

#### 2.1.2 FFmpeg进程管理

使用 `ProcessBuilder` 管理FFmpeg子进程生命周期：

```
┌──────────────────┐
│  FFmpegManager   │
├──────────────────┤
│ - start(cameraId)│
│ - stop(cameraId) │
│ - restart()      │
│ - getStatus()    │
│ - isAlive()      │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  Process (FFmpeg) │
│  - stdin 写入     │
│  - stdout 读取日志│
│  - stderr 读取错误│
└──────────────────┘
```

关键设计要点：
- FFmpeg进程作为Spring Boot应用的子进程启动
- 通过守护线程读取FFmpeg的标准输出/错误输出
- 实现优雅关闭：发送`q`命令到stdin，等待进程退出
- 异常退出时自动重启（可配置重试次数）

### 2.2 Netty RTSP Server

#### 2.2.1 RTSP协议交互流程

```
FFmpeg Client                          Netty RTSP Server
     │                                       │
     │────── OPTIONS ───────────────────────►│
     │◄───── 200 OK ────────────────────────│
     │                                       │
     │────── DESCRIBE ──────────────────────►│
     │◄───── 200 OK + SDP ──────────────────│
     │                                       │
     │────── SETUP (Track0, RTP) ───────────►│
     │◄───── 200 OK (Server Ports) ─────────│
     │                                       │
     │────── PLAY ──────────────────────────►│
     │◄───── 200 OK ────────────────────────│
     │                                       │
     │────── RTP Packets (流数据) ──────────►│
     │────── RTCP Packets ─────────────────►│
     │                                       │
     │────── TEARDOWN ──────────────────────►│
     │◄───── 200 OK ────────────────────────│
```

#### 2.2.2 Netty Handler Pipeline

```
ChannelPipeline:
┌───────────────────────────────────────────────────┐
│  RtspRequestDecoder     (解码RTSP请求)             │
├───────────────────────────────────────────────────┤
│  RtspResponseEncoder    (编码RTSP响应)             │
├───────────────────────────────────────────────────┤
│  RtspServerHandler      (RTSP信令处理)              │
│  ├── OPTIONS → 返回支持的方法                       │
│  ├── DESCRIBE → 返回SDP描述                         │
│  ├── SETUP → 协商RTP传输端口                        │
│  ├── PLAY → 开始接收RTP流                          │
│  └── TEARDOWN → 关闭会话                           │
├───────────────────────────────────────────────────┤
│  RtpPacketHandler       (RTP包解析)                 │
│  ├── 提取H.264 NALU                               │
│  ├── 重组分片帧(FU-A)                              │
│  └── 输出完整视频帧到下游                           │
├───────────────────────────────────────────────────┤
│  VideoFrameHandler        (视频帧处理)              │
│  ├── 写入TS分片缓冲区                             │
│  ├── 分发到WebSocket (实时预览)                     │
│  └── 触发分片上传 (MinIO)                          │
└───────────────────────────────────────────────────┘
```

#### 2.2.3 RTP包解析

RTP包头结构（12字节固定头）：
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
│V=2│P│X│  CC   │M│     PT      │       sequence number         │
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
│                           timestamp                           │
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
│                           SSRC                                │
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

H.264 over RTP 的NALU打包方式：
- **Single NALU**: 一个RTP包包含一个完整的NALU
- **Fragmentation Unit A (FU-A)**: 一个大NALU被分片到多个RTP包中
- **Aggregation Packet (STAP-A)**: 多个小NALU聚合到一个RTP包中

### 2.3 视频分片与存储

#### 2.3.1 分片策略

```
时间线:  ───────────────────────────────────────────────►
         │◄─── 10min ──►│◄─── 10min ──►│◄─── 10min ──►│
         │   Segment_1   │   Segment_2   │   Segment_3   │
         └──────┬────────┴──────┬────────┴──────┬───────┘
                │               │               │
                ▼               ▼               ▼
          camera1_001.ts  camera1_002.ts  camera1_003.ts
```

分片规则：
- 每个分片时长：10分钟（可配置）
- 文件格式：MPEG-TS（`.ts`）
- 命名规范：`{cameraId}/{yyyyMMdd}/{HHmmss}_{sequence}.ts`
- 示例：`camera1/20260509/143000_001.ts`

#### 2.3.2 存储流程

```
┌─────────────┐    ┌──────────────┐    ┌─────────────┐    ┌────────────┐
│ 视频帧流入   │───►│ TS复用器     │───►│ 分片完成检测  │───►│ MinIO上传  │
│ (H.264 NALU)│    │ (写TS文件)   │    │ (10min/大小) │    │            │
└─────────────┘    └──────────────┘    └─────────────┘    └─────┬──────┘
                                                                │
                                                   ┌────────────▼──────┐
                                                   │ 元数据入库         │
                                                   │ (时间、路径、时长) │
                                                   └───────────────────┘
```

#### 2.3.3 TS复用

使用 `MpegTsMuxer` 将H.264 NALU封装为MPEG-TS格式：

- 添加PAT (Program Association Table)
- 添加PMT (Program Map Table)
- 将H.264 NALU封装为PES包
- 将PES包分割为188字节的TS包

### 2.4 数据库设计

#### 2.4.1 视频分片表 (video_segment)

```sql
CREATE TABLE video_segment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    camera_id       VARCHAR(64)    NOT NULL COMMENT '摄像头ID',
    file_path       VARCHAR(512)   NOT NULL COMMENT 'MinIO中的对象路径',
    bucket_name     VARCHAR(128)   NOT NULL DEFAULT 'streams' COMMENT 'MinIO桶名',
    start_time      DATETIME(3)    NOT NULL COMMENT '视频开始时间',
    end_time        DATETIME(3)    NOT NULL COMMENT '视频结束时间',
    duration_ms     INT            NOT NULL COMMENT '视频时长(毫秒)',
    file_size       BIGINT         NOT NULL COMMENT '文件大小(字节)',
    resolution      VARCHAR(16)    COMMENT '分辨率,如1920x1080',
    codec           VARCHAR(16)    DEFAULT 'h264' COMMENT '视频编码',
    created_at      DATETIME       DEFAULT CURRENT_TIMESTAMP,
    expired_at      DATETIME       COMMENT '过期时间',
    INDEX idx_camera_time (camera_id, start_time, end_time),
    INDEX idx_expired (expired_at)
) COMMENT '视频分片索引表';
```

#### 2.4.2 摄像头配置表 (camera_config)

```sql
CREATE TABLE camera_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    camera_id       VARCHAR(64)    NOT NULL UNIQUE COMMENT '摄像头唯一标识',
    camera_name     VARCHAR(128)   NOT NULL COMMENT '摄像头显示名称',
    device_path     VARCHAR(256)   COMMENT '设备路径,如/dev/video0',
    resolution      VARCHAR(16)    DEFAULT '1920x1080' COMMENT '采集分辨率',
    framerate       INT            DEFAULT 25 COMMENT '采集帧率',
    bitrate_kbps    INT            DEFAULT 2000 COMMENT '编码码率(kbps)',
    rtsp_port       INT            DEFAULT 8554 COMMENT 'RTSP端口',
    enabled         TINYINT(1)     DEFAULT 1 COMMENT '是否启用',
    status          VARCHAR(16)    DEFAULT 'OFFLINE' COMMENT '状态: ONLINE/OFFLINE/ERROR',
    created_at      DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '摄像头配置表';
```

### 2.5 MinIO存储设计

#### 2.5.1 Bucket结构

```
streams/
├── camera1/
│   ├── 20260509/
│   │   ├── 143000_001.ts
│   │   ├── 144000_002.ts
│   │   └── 145000_003.ts
│   └── 20260510/
│       └── 090000_001.ts
└── camera2/
    └── ...
```

#### 2.5.2 上传策略

- 分片完成后异步上传到MinIO
- 使用 `putObject` 直接上传完整TS文件
- 上传成功后记录元数据到数据库
- 上传失败时重试（可配置重试次数和间隔）
- 支持配置MinIO Lifecycle规则自动清理过期对象

### 2.6 回放检索

#### 2.6.1 查询流程

```
┌──────────────┐    ┌──────────────────┐    ┌──────────────────┐
│ 前端请求      │───►│ REST API         │───►│ 数据库查询        │
│ cameraId +   │    │ /api/playback    │    │ 按时间范围检索    │
│ startTime +  │    │                  │    │ video_segment表   │
│ endTime      │    └──────────────────┘    └────────┬─────────┘
└──────────────┘                                     │
                                                     ▼
                                          ┌──────────────────┐
                                          │ 获取分片列表        │
                                          │ [seg1, seg2, ...] │
                                          └────────┬─────────┘
                                                   │
                                    ┌──────────────┴──────────────┐
                                    ▼                             ▼
                          ┌──────────────────┐        ┌──────────────────┐
                          │ 方案A: 合并后播放  │        │ 方案B: 播放列表   │
                          │ 下载合并为单文件   │        │ 按序返回MinIO URL │
                          └──────────────────┘        └──────────────────┘
```

#### 2.6.2 回放实现方案

**方案A - 服务端合并（推荐用于短时段回放）：**
1. 从MinIO下载匹配时间段的所有TS分片
2. 使用FFmpeg合并为单个MP4文件
3. 返回MP4的HTTP URL供前端播放

**方案B - 客户端顺序播放（推荐用于长时段回放）：**
1. 按时间顺序返回TS分片的MinIO预签名URL列表
2. 前端依次加载播放（HLS方式或顺序fetch）
3. 减少服务端计算压力

### 2.7 实时预览

#### 2.7.1 WebSocket推流方案

```
┌─────────────┐    RTP解析     ┌──────────────┐   WebSocket    ┌─────────────┐
│ 摄像头推流   │ ──────────►  │ H.264 NALU   │ ─────────────► │ 前端浏览器    │
│ (RTSP/Netty)│               │ 提取          │   (Binary)     │ (JS解码播放) │
└─────────────┘               └──────────────┘                └─────────────┘
```

前端播放方案：
- **方案1**: 使用 `Broadway.js` (纯JS H.264解码器) 解码NALU后在Canvas上渲染
- **方案2**: 服务端将H.264转封装为FLV，前端使用 `flv.js` 播放
- **方案3**: 服务端通过FFmpeg实时转码为WebRTC，前端使用WebRTC播放

**推荐方案2**：H.264 → FLV转封装 + flv.js播放，延迟低（<1s），兼容性好。

### 2.8 系统配置

#### 2.8.1 application.yml

```yaml
server:
  port: 8080

camera:
  rtsp:
    port: 8554                    # RTSP服务端口
  ffmpeg:
    path: ffmpeg                  # FFmpeg可执行文件路径
    framerate: 25                 # 帧率
    resolution: 1920x1080         # 分辨率
    bitrate-kbps: 2000            # 码率
    preset: ultrafast             # 编码速度
    device:                       # 摄像头设备路径 (Linux: /dev/video0, Windows: 设备名)
      linux: /dev/video0
      windows: "USB Camera"
  stream:
    segment-duration-minutes: 10  # 分片时长(分钟)
    segment-max-size-mb: 100      # 分片最大大小(MB)
    preview-buffer-size: 512      # 预览缓冲区大小(KB)
  storage:
    retention-days: 30            # 视频保留天数
    cleanup-cron: "0 0 2 * * ?"   # 清理任务cron表达式

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: streams

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/smart_camera?useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
```

## 3. 项目结构

```
SmartCamera/
├── pom.xml
├── docs/
│   ├── requirements.md           # 需求文档
│   └── technical-design.md       # 技术方案文档 (本文件)
├── src/
│   ├── main/
│   │   ├── java/com/smartcamera/
│   │   │   ├── SmartCameraApplication.java
│   │   │   ├── config/
│   │   │   │   ├── CameraProperties.java       # 摄像头配置属性
│   │   │   │   ├── MinioConfig.java            # MinIO配置
│   │   │   │   ├── NettyConfig.java            # Netty配置
│   │   │   │   └── WebConfig.java              # Web配置(CORS等)
│   │   │   ├── controller/
│   │   │   │   ├── CameraController.java       # 摄像头管理API
│   │   │   │   ├── PlaybackController.java     # 回放检索API
│   │   │   │   └── StreamController.java       # 流管理API
│   │   │   ├── service/
│   │   │   │   ├── CameraService.java          # 摄像头管理服务
│   │   │   │   ├── FfmpegManager.java          # FFmpeg进程管理
│   │   │   │   ├── StreamService.java          # 流管理服务
│   │   │   │   ├── StorageService.java         # 视频存储服务
│   │   │   │   ├── PlaybackService.java        # 回放检索服务
│   │   │   │   └── RecordScheduler.java        # 录制调度器
│   │   │   ├── netty/
│   │   │   │   ├── RtspServer.java             # Netty RTSP Server启动
│   │   │   │   ├── RtspServerInitializer.java  # Channel初始化
│   │   │   │   ├── RtspServerHandler.java      # RTSP信令处理
│   │   │   │   ├── RtpPacketHandler.java       # RTP包解析
│   │   │   │   ├── VideoFrameHandler.java      # 视频帧处理
│   │   │   │   ├── codec/
│   │   │   │   │   ├── H264Parser.java         # H.264 NALU解析
│   │   │   │   │   └── MpegTsMuxer.java        # MPEG-TS复用器
│   │   │   │   └── model/
│   │   │   │       ├── RtspMessage.java        # RTSP消息模型
│   │   │   │       ├── RtpPacket.java          # RTP包模型
│   │   │   │       └── SdpDescription.java     # SDP描述模型
│   │   │   ├── websocket/
│   │   │   │   ├── WebSocketConfig.java        # WebSocket配置
│   │   │   │   └── VideoWebSocketHandler.java  # 视频WebSocket处理
│   │   │   ├── entity/
│   │   │   │   ├── CameraConfig.java           # 摄像头配置实体
│   │   │   │   └── VideoSegment.java           # 视频分片实体
│   │   │   ├── repository/
│   │   │   │   ├── CameraConfigRepository.java
│   │   │   │   └── VideoSegmentRepository.java
│   │   │   └── model/
│   │   │       ├── dto/
│   │   │       │   ├── CameraDTO.java
│   │   │       │   └── PlaybackQueryDTO.java
│   │   │       └── vo/
│   │   │           ├── PlaybackResultVO.java
│   │   │           └── StreamStatusVO.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       └── application-prod.yml
│   └── test/
│       └── java/com/smartcamera/
│           ├── netty/
│           │   ├── RtspServerHandlerTest.java
│           │   └── RtpPacketHandlerTest.java
│           └── service/
│               ├── FfmpegManagerTest.java
│               └── StorageServiceTest.java
├── scripts/
│   ├── start-ffmpeg.sh           # Linux FFmpeg启动脚本
│   └── start-ffmpeg.bat          # Windows FFmpeg启动脚本
└── docker/
    ├── docker-compose.yml         # 一键部署(MinIO+MySQL)
    └── Dockerfile                 # 应用容器化
```

## 4. API设计

### 4.1 摄像头管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/cameras` | 获取摄像头列表 |
| POST | `/api/v1/cameras` | 新增摄像头配置 |
| PUT | `/api/v1/cameras/{id}` | 更新摄像头配置 |
| DELETE | `/api/v1/cameras/{id}` | 删除摄像头配置 |
| POST | `/api/v1/cameras/{id}/start` | 启动摄像头推流 |
| POST | `/api/v1/cameras/{id}/stop` | 停止摄像头推流 |
| GET | `/api/v1/cameras/{id}/status` | 获取摄像头状态 |

### 4.2 视频回放

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/playback/segments` | 查询视频分片列表 |
| GET | `/api/v1/playback/merge` | 获取合并后的视频URL |
| GET | `/api/v1/playback/playlist` | 获取播放列表(M3U8) |
| GET | `/api/v1/playback/download/{segmentId}` | 下载单个分片 |

### 4.3 实时预览

| 方法 | 路径 | 说明 |
|------|------|------|
| WS | `/ws/video/live/{cameraId}` | WebSocket实时视频流 |
| GET | `/api/v1/streams/{cameraId}/preview.m3u8` | HLS实时预览流 |

### 4.4 系统管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/system/status` | 获取系统状态 |
| DELETE | `/api/v1/storage/expired` | 清理过期视频 |
| GET | `/api/v1/storage/stats` | 获取存储统计 |

## 5. 关键依赖 (pom.xml)

```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- Netty -->
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
        <version>4.1.108.Final</version>
    </dependency>

    <!-- MinIO -->
    <dependency>
        <groupId>io.minio</groupId>
        <artifactId>minio</artifactId>
        <version>8.5.9</version>
    </dependency>

    <!-- MySQL -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

## 6. 前端设计

### 6.1 技术选型

| 组件 | 技术选型 | 说明 |
|------|----------|------|
| 框架 | Vue 3 + Composition API | 响应式前端框架 |
| 构建工具 | Vite 5 | 快速开发构建 |
| UI 组件库 | Element Plus | 浅色管理风格组件库 |
| 路由 | Vue Router 4 | 页面路由管理 |
| 状态管理 | Pinia | 全局状态管理 |
| HTTP 客户端 | Axios | REST API 调用 |
| 视频播放 | flv.js | HTTP-FLV 实时视频播放 |
| 视频播放 | video.js | HLS/MP4 历史视频回放 |
| 图表 | ECharts | 存储统计图表 |

### 6.2 项目结构

```
SmartCamera-Web/
├── package.json
├── vite.config.js
├── index.html
├── public/
│   └── favicon.ico
├── src/
│   ├── main.js
│   ├── App.vue
│   ├── router/
│   │   └── index.js                 # 路由配置
│   ├── stores/
│   │   ├── camera.js                # 摄像头状态 store
│   │   └── system.js                # 系统信息 store
│   ├── api/
│   │   ├── camera.js                # 摄像头相关 API
│   │   ├── playback.js              # 回放相关 API
│   │   └── system.js                # 系统管理 API
│   ├── views/
│   │   ├── Dashboard.vue            # 仪表盘（总览）
│   │   ├── Monitor.vue              # 实时监控页面
│   │   ├── Playback.vue             # 历史回放页面
│   │   ├── CameraManage.vue         # 摄像头管理页面
│   │   └── StorageManage.vue        # 存储管理页面
│   ├── components/
│   │   ├── VideoPlayer.vue          # 视频播放组件（封装 flv.js）
│   │   ├── CameraCard.vue           # 摄像头状态卡片
│   │   ├── TimelinePicker.vue       # 时间线选择器
│   │   ├── StorageChart.vue         # 存储用量图表
│   │   └── Layout/
│   │       ├── Sidebar.vue          # 侧边栏导航
│   │       └── Header.vue           # 顶部导航栏
│   ├── styles/
│   │   ├── variables.scss           # 样式变量
│   │   └── global.scss              # 全局样式
│   └── utils/
│       ├── request.js               # Axios 封装
│       └── format.js                # 工具函数（时间格式化等）
└── .env.development                 # 开发环境变量
```

### 6.3 页面设计

#### 6.3.1 仪表盘页面 (Dashboard.vue)

```
┌──────────────────────────────────────────────────────────────┐
│  仪表盘                                                        │
├──────────────────────────────────────────────────────────────┤
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────┐  │
│  │ 摄像头总数  │  │ 在线摄像头  │  │ 录制中     │  │ 存储用量│  │
│  │     1      │  │     1      │  │     1      │  │ 45.2GB │  │
│  └────────────┘  └────────────┘  └────────────┘  └────────┘  │
│                                                              │
│  ┌─────────────────────────────┐  ┌──────────────────────┐  │
│  │  摄像头状态列表               │  │  存储用量趋势 (7天)   │  │
│  │  ┌───────────────────────┐  │  │  ┌────────────────┐  │  │
│  │  │ 🟢 USB Camera 1       │  │  │  │  ▓▓▓▓░░ 趋势图  │  │  │
│  │  │    1920x1080 @ 25fps  │  │  │  │                │  │  │
│  │  │    已录制: 2h 35min   │  │  │  └────────────────┘  │  │
│  │  └───────────────────────┘  │  │                      │  │
│  │                             │  │  [快速跳转实时监控 →] │  │
│  └─────────────────────────────┘  └──────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

#### 6.3.2 实时监控页面 (Monitor.vue)

```
┌──────────────────────────────────────────────────────────────┐
│  实时监控                                    [刷新] [全屏]     │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                                                      │   │
│  │                                                      │   │
│  │                  实时视频画面                         │   │
│  │              (flv.js 播放 RTSP 转 FLV 流)              │   │
│  │                                                      │   │
│  │                                                      │   │
│  │  ┌──────────────────────┐                            │   │
│  │  │ 🟢 在线 | 25fps       │  ← 状态浮层                 │   │
│  │  │ 1920x1080 | 延迟: 0.8s│                            │   │
│  │  └──────────────────────┘                            │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  摄像头: [USB Camera 1 ▼]   分辨率: [1920x1080 ▼]            │
│  [截图] [开始录制] [停止录制]                                 │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

视频播放流程：
```
前端 flv.js ───HTTP GET───► Spring Boot ───推流──► Netty
     │                        │
     │◄── HTTP-FLV 流 ────────┘
     │
  解码播放 → Canvas 渲染
```

#### 6.3.3 历史回放页面 (Playback.vue)

```
┌──────────────────────────────────────────────────────────────┐
│  历史回放                                                      │
├──────────────────────────────────────────────────────────────┤
│  摄像头: [USB Camera 1 ▼]                                    │
│  日期: [2026-05-09 ▼]    [搜索]                               │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  00:00  02:00  04:00  06:00  08:00  10:00  12:00    │   │
│  │  ├─────┤                                             │   │
│  │  │█████│░░░░░░░░░░█████████░░░░███████░░░░░░░░░░    │   │
│  │  └─────┴───────────────────────────────────────────  │   │
│  │   有录像时段          无录像时段                       │   │
│  │                                                      │   │
│  │  ← 时间轴 (可拖动选择起止时间) →                       │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  选中段: 14:30:00 ~ 14:50:00 (20分钟)    [播放] [下载]       │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                                                      │   │
│  │              回放视频画面                              │   │
│  │           (video.js 播放 MP4/HLS)                     │   │
│  │                                                      │   │
│  └──────────────────────────────────────────────────────┘   │
│  [◀◀] [▶] [▶▶] [1.0x▼] ────●─────────── 20:00              │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

回放流程：
```
1. 用户选择时间范围 → 前端调用 GET /api/v1/playback/merge
2. 后端从 MinIO 拉取对应分片 → FFmpeg 合并为 MP4
3. 返回 MP4 的预签名 URL → 前端 video.js 播放
```

#### 6.3.4 摄像头管理页面 (CameraManage.vue)

```
┌──────────────────────────────────────────────────────────────┐
│  摄像头管理                              [+ 新增摄像头]        │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 摄像头名称 │ 设备路径   │ 分辨率     │ 状态   │ 操作    │   │
│  ├────────────┼────────────┼────────────┼────────┼────────┤   │
│  │ USB Camera │ /dev/video │ 1920x1080  │ 🟢在线  │ 编辑   │   │
│  │            │ 0          │ @ 25fps   │ 录制中  │ 停止   │   │
│  │            │            │            │        │ 删除   │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  新增/编辑弹窗:                                               │
│  ┌──────────────────────────────────────────┐               │
│  │  摄像头名称: [USB Camera 2       ]        │               │
│  │  设备路径:   [/dev/video1         ]        │               │
│  │  分辨率:     [1920x1080       ▼]          │               │
│  │  帧率:       [25              ▼]          │               │
│  │  码率(kbps): [2000            ]           │               │
│  │                        [取消]  [确定]      │               │
│  └──────────────────────────────────────────┘               │
└──────────────────────────────────────────────────────────────┘
```

#### 6.3.5 存储管理页面 (StorageManage.vue)

```
┌──────────────────────────────────────────────────────────────┐
│  存储管理                                                      │
├──────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ 已用存储      │  │ 视频分片总数  │  │ 保留天数      │       │
│  │   45.2 GB    │  │    1,286     │  │    30 天     │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                              │
│  存储用量趋势 (30天)                                          │
│  ┌────────────────────────────────────────────────────┐     │
│  │  GB                                                │     │
│  │  50┤          ╱╲    ╱╲                              │     │
│  │  40┤    ╱╲  ╱    ╲╱    ╲╱╲                        │     │
│  │  30┤  ╱    ╲                                      │     │
│  │  20┤╱                                              │     │
│  │    └───────────────────────────────────────────    │     │
│  │     1  5  10  15  20  25  30 (天)                  │     │
│  └────────────────────────────────────────────────────┘     │
│                                                              │
│  设置: 视频保留天数 [30] 天    [保存设置]                     │
│        [立即清理过期视频]                                     │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.4 前端核心组件实现

#### 6.4.1 VideoPlayer.vue (flv.js 实时播放)

```vue
<template>
  <div class="video-player">
    <video ref="videoRef" controls autoplay muted class="video-el" />
    <div v-if="error" class="video-error">{{ error }}</div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import flvjs from 'flv.js'

const props = defineProps({
  src: { type: String, required: true },
  autoplay: { type: Boolean, default: true }
})

const videoRef = ref(null)
const error = ref('')
let player = null

onMounted(() => {
  if (flvjs.isSupported()) {
    player = flvjs.createPlayer({ type: 'flv', url: props.src }, {
      enableWorker: true,
      enableStashBuffer: false,  // 低延迟
      stashInitialSize: 128
    })
    player.attachMediaElement(videoRef.value)
    player.load()
    player.play().catch(e => { error.value = '播放失败: ' + e.message })
  } else {
    error.value = '当前浏览器不支持 FLV 播放'
  }
})

onUnmounted(() => {
  player?.destroy()
})
</script>
```

#### 6.4.2 TimelinePicker.vue (时间线选择器)

核心功能：
- 展示一天内哪些时段有录像（绿色高亮条）
- 支持拖动选择起止时间
- 点击跳转到对应时段
- 与 ECharts 结合展示统计信息

### 6.5 前后端联调配置

#### 6.5.1 Vite 代理配置 (vite.config.js)

```js
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  }
})
```

#### 6.5.2 CORS 配置 (后端 Spring Boot)

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}
```

## 7. 部署方案

### 6.1 Docker Compose 部署

```yaml
version: '3.8'

services:
  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - minio-data:/data
    command: server /data --console-address ":9001"

  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: smart_camera
    volumes:
      - mysql-data:/var/lib/mysql

  smart-camera:
    build: .
    ports:
      - "8080:8080"
      - "8554:8554"
    depends_on:
      - minio
      - mysql
    devices:
      - "/dev/video0:/dev/video0"  # 映射摄像头设备
    environment:
      - SPRING_PROFILES_ACTIVE=prod

volumes:
  minio-data:
  mysql-data:
```

### 6.2 本地开发部署步骤

1. **安装依赖**
   - 安装 FFmpeg (`sudo apt install ffmpeg` 或 Windows下载安装)
   - 安装 MinIO (下载二进制或使用Docker)
   - 安装 MySQL (或使用Docker)

2. **启动基础设施**
   ```bash
   docker-compose up -d minio mysql
   ```

3. **创建MinIO Bucket**
   ```bash
   mc alias set local http://localhost:9000 minioadmin minioadmin
   mc mb local/streams
   ```

4. **启动应用**
   ```bash
   mvn spring-boot:run
   ```

5. **启动推流**
   - 通过API调用启动摄像头推流
   - 或手动执行FFmpeg命令

## 8. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| FFmpeg进程异常退出 | 录制中断 | 实现进程守护，自动重启；监控告警 |
| MinIO连接断开 | 存储失败 | 本地缓存缓冲区，连接恢复后补传 |
| 网络带宽不足 | 丢帧/卡顿 | 动态调整码率；QoS保障 |
| 磁盘/存储满载 | 无法写入 | 监控存储容量；自动清理过期数据 |
| 多线程并发安全 | 数据不一致 | Netty Handler无状态设计；DB使用事务 |
| RTSP协议兼容性 | 推流失败 | 严格遵循RFC 2326；充分测试 |

## 9. 后续扩展方向

- **AI分析集成**: 在视频帧处理链中接入目标检测/人脸识别模型
- **多协议支持**: 扩展支持RTMP、GB28181、ONVIF等协议
- **边缘计算**: 在边缘设备上执行FFmpeg编码，中心节点只做存储和分发
- **集群部署**: 支持多节点部署，通过消息队列实现负载均衡
- **移动端**: 提供iOS/Android客户端用于监控和回放
