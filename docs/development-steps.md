# 本地摄像头监控系统 - 开发步骤文档

## 1. 开发阶段总览

| 阶段 | 内容 | 预计产出 | 依赖 |
|------|------|----------|------|
| Phase 1 | 项目初始化与基础设施 | 项目骨架、Docker环境、数据库 | 无 |
| Phase 2 | FFmpeg推流模块 | 摄像头采集、RTSP推流、进程管理 | Phase 1 |
| Phase 3 | Netty RTSP Server | RTSP信令处理、RTP包解析 | Phase 1 |
| Phase 4 | 视频分片与MinIO存储 | TS复用、分片上传、元数据入库 | Phase 3 |
| Phase 5 | 实时预览分发 | HTTP-FLV转封装、WebSocket推流 | Phase 3, Phase 4 |
| Phase 6 | 回放检索API | 时间范围查询、分片合并、播放列表 | Phase 4 |
| Phase 7 | 摄像头管理与系统管理 | CRUD API、状态监控、过期清理 | Phase 2, Phase 4 |
| Phase 8 | 前端项目搭建 | Vue3项目骨架、路由、布局 | 无（可与后端并行） |
| Phase 9 | 前端页面开发 | 仪表盘、监控、回放、管理页面 | Phase 6, Phase 7 |
| Phase 10 | 联调测试与优化 | 端到端测试、性能调优、部署 | Phase 5 ~ Phase 9 |

---

## 2. Phase 1: 项目初始化与基础设施

### 2.1 后端项目初始化

**目标**: 创建Spring Boot项目骨架，配置基础依赖和结构。

**步骤**:

1. 使用 Spring Initializr 创建项目：
   - Spring Boot 3.2.x
   - Dependencies: Spring Web, Spring WebSocket, Spring Data JPA, MySQL Driver, Lombok, Validation

2. 创建项目目录结构（参考技术方案文档 第3节 项目结构）

3. 编写 `pom.xml`，补充关键依赖：
   - `io.netty:netty-all:4.1.108.Final`
   - `io.minio:minio:8.5.9`

4. 创建 `SmartCameraApplication.java` 启动类

### 2.2 配置文件编写

**目标**: 完成 `application.yml` 及各环境配置。

**步骤**:

1. 编写 `src/main/resources/application.yml`（参考技术方案文档 第2.8.1节）
2. 编写 `application-dev.yml`（开发环境，连接本地MySQL/MinIO）
3. 编写 `application-prod.yml`（生产环境，使用环境变量注入敏感配置）

### 2.3 Docker基础设施部署

**目标**: 一键启动MySQL和MinIO。

**步骤**:

1. 创建 `docker/docker-compose.yml`（参考技术方案文档 第6.1节）
2. 执行 `docker-compose up -d minio mysql` 启动
3. 创建MinIO Bucket：
   ```bash
   mc alias set local http://localhost:9000 minioadmin minioadmin
   mc mb local/streams
   ```

### 2.4 数据库初始化

**目标**: 创建数据库表结构。

**步骤**:

1. 创建 `camera_config` 表（参考技术方案文档 第2.4.2节）
2. 创建 `video_segment` 表（参考技术方案文档 第2.4.1节）
3. 配置 JPA `ddl-auto: update` 或手动执行SQL脚本

### 2.5 配置类编写

**目标**: 编写Spring配置类。

**步骤**:

1. `CameraProperties.java` — 绑定 `camera.*` 配置属性
2. `MinioConfig.java` — 创建 `MinioClient` Bean
3. `NettyConfig.java` — Netty线程池、Boss/Worker Group配置
4. `WebConfig.java` — CORS配置（参考技术方案文档 第6.5.2节）

**验收标准**:
- [ ] `mvn spring-boot:run` 能成功启动
- [ ] MySQL连接正常，表创建成功
- [ ] MinIO连接正常，Bucket存在
- [ ] Docker Compose 一键部署可用

---

## 3. Phase 2: FFmpeg推流模块

### 3.1 FFmpeg命令封装

**目标**: 将FFmpeg推流命令参数化。

**步骤**:

1. 创建 `FfmpegCommandBuilder.java`
2. 根据操作系统动态选择设备路径（Linux: `/dev/video0`, Windows: `video="USB Camera"`）
3. 支持配置分辨率、帧率、码率、编码preset
4. 输出RTSP地址格式：`rtsp://localhost:{port}/live/{cameraId}`

### 3.2 FFmpeg进程管理

**目标**: 实现FFmpeg子进程的生命周期管理。

**步骤**:

1. 创建 `FfmpegManager.java`
2. 核心方法：
   - `start(cameraId)` — 启动FFmpeg进程
   - `stop(cameraId)` — 优雅关闭（发送`q`到stdin）
   - `restart(cameraId)` — 重启
   - `isAlive(cameraId)` — 检查进程状态
   - `getStatus(cameraId)` — 获取运行状态
3. 使用 `ProcessBuilder` 启动进程
4. 启动守护线程读取stdout和stderr日志
5. 添加进程退出监听，实现自动重试（可配置最大重试次数）
6. Spring `@PreDestroy` 中关闭所有FFmpeg进程

### 3.3 推流脚本

**目标**: 提供独立的启动脚本方便调试。

**步骤**:

1. 创建 `scripts/start-ffmpeg.sh`（Linux）
2. 创建 `scripts/start-ffmpeg.bat`（Windows）
3. 脚本支持传入设备路径、分辨率、码率等参数

**验收标准**:
- [ ] FFmpeg能成功从摄像头采集视频并推送到 `rtsp://localhost:8554/live/camera1`
- [ ] 可通过API启动/停止推流
- [ ] 进程异常退出后能自动重启
- [ ] 应用关闭时FFmpeg进程被正确清理
- [ ] 使用VLC播放器能打开RTSP流并正常播放

---

## 4. Phase 3: Netty RTSP Server

### 4.1 Netty Server启动

**目标**: 在Spring Boot中嵌入Netty RTSP Server。

**步骤**:

1. 创建 `RtspServer.java` — Netty服务器启动类
2. 绑定RTSP端口（默认8554）
3. 使用Spring的 `SmartLifecycle` 接口实现随应用启动/关闭
4. 配置Boss和Worker EventLoopGroup

### 4.2 RTSP信令处理

**目标**: 实现RTSP协议的核心方法。

**步骤**:

1. 创建 `RtspServerInitializer.java` — ChannelPipeline配置
2. 创建 `RtspServerHandler.java` — 继承 `SimpleChannelInboundHandler<FullHttpRequest>`
3. 实现以下RTSP方法：
   - `OPTIONS` — 返回支持的方法列表
   - `DESCRIBE` — 返回SDP描述（包含视频编码、分辨率、帧率）
   - `SETUP` — 协商RTP传输端口（Server端端口分配）
   - `PLAY` — 开始接收RTP流，创建Session
   - `TEARDOWN` — 关闭Session，释放端口资源
4. 创建 `RtspMessage.java` — RTSP请求/响应模型
5. 创建 `SdpDescription.java` — SDP描述模型
6. 实现Session管理（Map<sessionId, RtspSession>）

### 4.3 RTP包解析

**目标**: 解析RTP包，提取H.264 NALU。

**步骤**:

1. 创建 `RtpPacketHandler.java`
2. 解析RTP固定头（12字节）：version、payload type、sequence number、timestamp、SSRC
3. 创建 `RtpPacket.java` — RTP包数据模型
4. 实现H.264 NALU重组：
   - Single NALU — 直接提取
   - FU-A分片 — 缓存分片，Start/End标记重组完整NALU
   - STAP-A聚合 — 拆分为多个NALU
5. 创建 `H264Parser.java` — 解析NALU类型（SPS/PPS/IDR/非IDR）

**验收标准**:
- [ ] FFmpeg能成功与RTSP Server完成DESCRIBE/SETUP/PLAY握手
- [ ] SDP描述正确返回
- [ ] 能正确接收并解析RTP包
- [ ] 能重组出完整的H.264 NALU
- [ ] TEARDOWN后Session正确清理

---

## 5. Phase 4: 视频分片与MinIO存储

### 5.1 MPEG-TS复用器

**目标**: 将H.264 NALU封装为MPEG-TS格式。

**步骤**:

1. 创建 `MpegTsMuxer.java`
2. 实现：
   - 生成PAT（Program Association Table）
   - 生成PMT（Program Map Table）
   - 将H.264 NALU封装为PES包
   - 将PES包分割为188字节的TS包
3. 输出为字节流，写入文件缓冲区

### 5.2 分片管理

**目标**: 按固定时长切割视频流。

**步骤**:

1. 创建 `SegmentManager.java`
2. 核心逻辑：
   - 维护当前分片的写入缓冲区（本地临时文件）
   - 检测分片完成条件（达到10分钟或达到最大文件大小）
   - 分片完成后触发上传任务
   - 创建下一个分片
3. 分片命名：`{cameraId}/{yyyyMMdd}/{HHmmss}_{sequence}.ts`
4. 本地临时文件存储路径可配置

### 5.3 MinIO上传

**目标**: 将TS分片上传到MinIO。

**步骤**:

1. 创建 `StorageService.java`
2. 核心方法：
   - `uploadSegment(cameraId, filePath, fileName)` — 上传TS文件到MinIO
   - `getSegmentUrl(objectName)` — 获取预签名下载URL
   - `deleteSegment(objectName)` — 删除MinIO对象
3. 使用 `MinioClient.putObject()` 上传
4. 异步上传（使用 `@Async` 或独立线程池）
5. 上传失败重试机制

### 5.4 元数据入库

**目标**: 记录视频分片索引。

**步骤**:

1. 创建实体类：
   - `CameraConfig.java` — 映射 `camera_config` 表
   - `VideoSegment.java` — 映射 `video_segment` 表
2. 创建Repository接口：
   - `CameraConfigRepository.java`
   - `VideoSegmentRepository.java`
3. 分片上传成功后，写入 `video_segment` 记录
4. 查询方法：
   - `findByCameraIdAndStartTimeBetween(cameraId, start, end)`
   - `findByExpiredAtBefore(now)` — 查询过期分片

**验收标准**:
- [ ] H.264流能正确封装为TS格式
- [ ] TS文件能用VLC正常播放
- [ ] 分片按10分钟自动切割
- [ ] TS文件成功上传到MinIO
- [ ] 数据库中有正确的分片索引记录
- [ ] 上传失败时能重试

---

## 6. Phase 5: 实时预览分发

### 6.1 HTTP-FLV转封装

**目标**: 将H.264 NALU实时转封装为FLV流。

**步骤**:

1. 创建 `FlvMuxer.java` — FLV格式复用器
2. 将H.264 NALU封装为FLV Tag（Video Tag）
3. 生成FLV Header和Previous Tag Size
4. 支持AVC Sequence Header（SPS/PPS）

### 6.2 HTTP-FLV服务

**目标**: 通过HTTP提供FLV实时流。

**步骤**:

1. 创建 `StreamController.java`
2. 接口：`GET /api/v1/streams/{cameraId}/live.flv`
3. 使用 `StreamingResponseBody` 将FLV流写入HTTP Response
4. 设置正确的Content-Type：`video/x-flv`
5. 前端多个连接共享同一份NALU数据（发布-订阅模式）

### 6.3 WebSocket实时推流

**目标**: 通过WebSocket推送实时视频帧。

**步骤**:

1. 创建 `WebSocketConfig.java` — 注册WebSocket端点
2. 创建 `VideoWebSocketHandler.java`
3. 端点：`/ws/video/live/{cameraId}`
4. 将NALU以Binary Message推送到前端
5. 管理WebSocket Session，连接断开时清理

### 6.4 帧分发机制

**目标**: 实现一对多的帧分发。

**步骤**:

1. 创建 `FrameDistributor.java`
2. 维护 `Map<cameraId, List<Channel/Sessions>>`
3. 从RtpPacketHandler接收NALU后分发给所有订阅者
4. 缓冲区管理：慢消费者丢弃策略

**验收标准**:
- [ ] 通过浏览器访问 `/api/v1/streams/{cameraId}/live.flv` 能收到FLV流
- [ ] flv.js能成功播放实时视频
- [ ] 多客户端同时观看同一路流
- [ ] 端到端延迟 < 3秒
- [ ] WebSocket连接正常收发

---

## 7. Phase 6: 回放检索API

### 7.1 分片查询

**目标**: 按时间范围查询历史分片。

**步骤**:

1. 创建 `PlaybackController.java`
2. 接口：`GET /api/v1/playback/segments`
3. 查询参数：`cameraId`, `startTime`, `endTime`
4. 返回分片列表（按start_time排序）

### 7.2 分片合并（服务端）

**目标**: 将多个TS分片合并为单个MP4。

**步骤**:

1. 在 `PlaybackService.java` 中实现合并逻辑
2. 流程：
   - 从MinIO下载匹配时间段的所有TS分片到本地临时目录
   - 使用 `FfmpegCommandBuilder` 构建合并命令：
     ```bash
     ffmpeg -i "concat:seg1.ts|seg2.ts|seg3.ts" -c copy -y output.mp4
     ```
   - 将MP4上传到MinIO临时目录
   - 返回预签名URL
3. 合并完成后清理本地临时文件
4. 设置临时URL过期时间（如1小时）

### 7.3 播放列表生成

**目标**: 生成M3U8播放列表。

**步骤**:

1. 接口：`GET /api/v1/playback/playlist`
2. 按时间顺序拼接TS分片的预签名URL
3. 生成M3U8格式：
   ```
   #EXTM3U
   #EXT-X-VERSION:3
   #EXTINF:600.0,
   {presigned_url_1}
   #EXTINF:600.0,
   {presigned_url_2}
   #EXT-X-ENDLIST
   ```

### 7.4 分片下载

**目标**: 下载单个TS分片。

**步骤**:

1. 接口：`GET /api/v1/playback/download/{segmentId}`
2. 查询数据库获取MinIO对象路径
3. 返回预签名下载URL或直接流式返回

**验收标准**:
- [ ] 按时间范围能正确查询到分片列表
- [ ] 合并后的MP4能正常播放
- [ ] M3U8播放列表能被video.js加载
- [ ] 单个分片能正常下载
- [ ] 合并任务完成后临时文件被清理

---

## 8. Phase 7: 摄像头管理与系统管理

### 8.1 摄像头管理API

**目标**: 实现摄像头的CRUD操作。

**步骤**:

1. 创建 `CameraController.java`
2. 接口：
   - `GET /api/v1/cameras` — 摄像头列表
   - `POST /api/v1/cameras` — 新增摄像头
   - `PUT /api/v1/cameras/{id}` — 更新摄像头
   - `DELETE /api/v1/cameras/{id}` — 删除摄像头
   - `POST /api/v1/cameras/{id}/start` — 启动推流
   - `POST /api/v1/cameras/{id}/stop` — 停止推流
   - `GET /api/v1/cameras/{id}/status` — 获取状态
3. 在Service层调用 `FfmpegManager` 启动/停止推流
4. 更新 `camera_config` 表中的status字段

### 8.2 系统状态

**目标**: 获取系统运行状态。

**步骤**:

1. 接口：`GET /api/v1/system/status`
2. 返回：
   - 摄像头在线数量
   - 录制中数量
   - 存储用量（已用/总容量）
   - FFmpeg进程状态

### 8.3 存储管理

**目标**: 存储统计与过期清理。

**步骤**:

1. 接口：`GET /api/v1/storage/stats` — 存储统计
2. 接口：`DELETE /api/v1/storage/expired` — 清理过期视频
3. 创建 `RecordScheduler.java`
4. 使用 `@Scheduled` 定时清理过期分片（默认每天凌晨2点）
5. 清理逻辑：
   - 查询 `expired_at < NOW()` 的分片
   - 从MinIO删除对象
   - 从数据库删除记录

### 8.4 全局异常处理

**目标**: 统一异常处理。

**步骤**:

1. 创建 `GlobalExceptionHandler.java`
2. 使用 `@RestControllerAdvice` 捕获异常
3. 统一返回格式：`{ code, message, data }`

**验收标准**:
- [ ] 摄像头CRUD操作正常
- [ ] 启动/停止推流正常
- [ ] 系统状态接口返回正确数据
- [ ] 过期分片能定时清理
- [ ] 异常返回统一格式

---

## 9. Phase 8: 前端项目搭建

### 9.1 项目初始化

**目标**: 创建Vue3前端项目骨架。

**步骤**:

1. 创建项目（在 `SmartCamera-Web/` 目录下）：
   ```bash
   npm create vite@latest . -- --template vue
   ```
2. 安装依赖：
   ```bash
   npm install vue-router pinia axios element-plus
   npm install flv.js video.js echarts
   npm install sass -D
   ```
3. 配置 `vite.config.js`（代理配置参考技术方案文档 第6.5.1节）
4. 配置 `.env.development`：
   ```
   VITE_API_BASE_URL=http://localhost:8080
   ```

### 9.2 项目结构搭建

**目标**: 搭建目录结构和基础文件。

**步骤**:

1. 按技术方案文档 第6.2节 创建目录结构
2. 配置 `src/router/index.js` — 路由定义
3. 配置 `src/stores/` — Pinia Store
4. 封装 `src/utils/request.js` — Axios实例（拦截器、Token处理）
5. 创建 `src/styles/global.scss` — 全局样式

### 9.3 布局组件

**目标**: 实现浅色管理风格的页面布局。

**步骤**:

1. 创建 `src/components/Layout/Sidebar.vue` — 侧边栏导航
   - 菜单项：仪表盘、实时监控、历史回放、摄像头管理、存储管理
2. 创建 `src/components/Layout/Header.vue` — 顶部栏
   - 显示系统名称、当前时间、系统状态
3. 创建 `src/App.vue` — 主布局（侧边栏 + 顶部 + 内容区）

**验收标准**:
- [ ] `npm run dev` 能正常启动开发服务器
- [ ] 代理配置正确，能转发到后端API
- [ ] 布局框架显示正常（浅色风格）
- [ ] 侧边栏导航能正常跳转

---

## 10. Phase 9: 前端页面开发

### 10.1 仪表盘页面

**目标**: 展示系统概览信息。

**步骤**:

1. 创建 `src/views/Dashboard.vue`
2. 顶部4个统计卡片：摄像头总数、在线数量、录制中、存储用量
3. 左侧：摄像头状态列表（复用 CameraCard 组件）
4. 右侧：存储用量趋势图（ECharts）
5. 调用 `/api/v1/system/status` 获取数据

### 10.2 实时监控页面

**目标**: 实时查看摄像头画面。

**步骤**:

1. 创建 `src/views/Monitor.vue`
2. 创建 `src/components/VideoPlayer.vue`（封装flv.js，参考技术方案文档 第6.4.1节）
3. 视频源：`/api/v1/streams/{cameraId}/live.flv`
4. 摄像头下拉选择器
5. 状态浮层：在线状态、分辨率、帧率、延迟
6. 截图按钮（使用Canvas截图）

### 10.3 历史回放页面

**目标**: 按时间检索并回放历史视频。

**步骤**:

1. 创建 `src/views/Playback.vue`
2. 创建 `src/components/TimelinePicker.vue` — 时间轴选择器
   - 展示24小时内哪些时段有录像
   - 拖动选择起止时间
3. 摄像头选择器 + 日期选择器
4. 调用 `/api/v1/playback/segments` 获取分片列表
5. 调用 `/api/v1/playback/merge` 获取MP4预签名URL
6. 使用 video.js 播放MP4
7. 播放控制：播放/暂停、倍速、进度条

### 10.4 摄像头管理页面

**目标**: 管理摄像头配置。

**步骤**:

1. 创建 `src/views/CameraManage.vue`
2. Element Plus Table 展示摄像头列表
3. 状态列显示在线/离线图标
4. 操作列：编辑、启动/停止、删除
5. 新增/编辑弹窗（Element Plus Dialog）
   - 表单字段：名称、设备路径、分辨率、帧率、码率
6. 操作确认后调用对应API

### 10.5 存储管理页面

**目标**: 查看和管理存储使用情况。

**步骤**:

1. 创建 `src/views/StorageManage.vue`
2. 顶部3个统计卡片：已用存储、分片总数、保留天数
3. ECharts折线图展示30天存储用量趋势
4. 保留天数设置（InputNumber + 保存按钮）
5. "立即清理过期视频"按钮（调用DELETE接口）

**验收标准**:
- [ ] 仪表盘数据正确显示
- [ ] 实时监控画面流畅播放，延迟 < 3秒
- [ ] 时间轴能正确显示有录像的时段
- [ ] 历史视频能正常回放
- [ ] 摄像头增删改查操作正常
- [ ] 存储统计图表正确显示

---

## 11. Phase 10: 联调测试与优化

### 11.1 端到端测试

**目标**: 验证完整业务流程。

**步骤**:

1. 启动所有基础设施（Docker Compose）
2. 启动Spring Boot后端
3. 启动前端开发服务器
4. 测试完整流程：
   - 添加摄像头 → 启动推流 → 实时预览
   - 等待分片生成 → 查询分片 → 回放历史视频
   - 停止推流 → 删除摄像头
   - 验证过期清理任务

### 11.2 性能测试

**目标**: 验证非功能需求。

**步骤**:

1. 延迟测试：记录从摄像头采集到前端显示的端到端延迟
2. 长时间运行测试：连续运行24小时，检查内存泄漏和稳定性
3. 存储效率测试：记录1080P视频每小时的实际存储大小
4. FFmpeg断流恢复测试：手动kill FFmpeg进程，验证自动重启

### 11.3 部署准备

**目标**: 完成生产部署配置。

**步骤**:

1. 完善 `docker/Dockerfile` — 应用容器化（包含FFmpeg）
2. 完善 `docker/docker-compose.yml` — 加入前端Nginx和应用服务
3. 编写 `application-prod.yml` — 生产环境配置
4. 配置日志级别和日志文件输出
5. 编写部署文档（`docs/deployment.md`）

### 11.4 文档完善

**目标**: 补充完整的项目文档。

**步骤**:

1. 更新 `README.md` — 项目介绍、快速开始
2. 编写 `docs/api.md` — API接口文档（可使用SpringDoc自动生成）
3. 编写 `docs/deployment.md` — 部署指南
4. 编写 `docs/troubleshooting.md` — 常见问题排查

**验收标准**:
- [ ] 端到端全流程走通无错误
- [ ] 延迟 < 3秒（NF-02）
- [ ] 连续运行8小时无内存泄漏
- [ ] 1080P视频每小时存储 < 2GB（NF-04）
- [ ] FFmpeg异常退出后30秒内自动恢复（NF-03）
- [ ] Docker Compose 一键部署可用
- [ ] 文档齐全

---

## 12. 开发优先级建议

如果资源有限，建议按以下优先级逐步推进：

| 优先级 | 阶段 | 说明 |
|--------|------|------|
| P0 | Phase 1 ~ Phase 4 | 核心链路：采集 → 推流 → 接收 → 存储 |
| P1 | Phase 7 | 管理功能：摄像头CRUD、系统状态 |
| P2 | Phase 5 | 实时预览：让前端能看到画面 |
| P3 | Phase 6 | 回放检索：完整回放能力 |
| P4 | Phase 8 ~ Phase 9 | 前端页面：用户界面 |
| P5 | Phase 10 | 联调优化：性能与部署 |

P0完成后，系统已具备核心的视频采集与存储能力，后续阶段可以逐步叠加。
