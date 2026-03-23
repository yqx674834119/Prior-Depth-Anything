#include <WiFi.h>
#include <WiFiClient.h>
#include "esp_camera.h"
#include "esp_http_server.h"

// ==========================================
// WiFi & 网络设置 (新手必看：手机要连这个WiFi)
// ==========================================
const char* ssid = "ToFSense_AP";
const char* password = "password123";

WiFiServer tcp_server(8080); // ToF 深度数据的 TCP 传输端口
WiFiClient tcp_client;

// ==========================================
// 硬件引脚配置 (ESP32S3-EYE 专用)
// ==========================================

// 1. TOFSense 雷达的接线引脚 (因为板载摄像头占用了 17/18，我们必须换两个空闲的引脚)
// 请用杜邦线将 TOFSense 雷达接在这两个 ESP32 引脚上：
#define TOF_RXD2 43 // ESP32-S3 此引脚接 TOF 的 TX 
#define TOF_TXD2 44 // ESP32-S3 此引脚接 TOF 的 RX 

// 2. 自带摄像头排线的内部引脚 (对应 CAMERA_MODEL_ESP32S3_EYE)
// 这些是板子内部已经焊好的线，你不需要管，千万别去改这里的数字！
#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     15
#define SIOD_GPIO_NUM     4
#define SIOC_GPIO_NUM     5

#define Y9_GPIO_NUM       16 // D7
#define Y8_GPIO_NUM       17 // D6
#define Y7_GPIO_NUM       18 // D5
#define Y6_GPIO_NUM       12 // D4
#define Y5_GPIO_NUM       10 // D3
#define Y4_GPIO_NUM       8  // D2
#define Y3_GPIO_NUM       9  // D1
#define Y2_GPIO_NUM       11 // D0

#define VSYNC_GPIO_NUM    6
#define HREF_GPIO_NUM     7
#define PCLK_GPIO_NUM     13


// ==========================================
// ToF 变量与缓存
// ==========================================
#define FRAME_LENGTH 400
uint8_t buffer[FRAME_LENGTH];
int bufferIndex = 0;
unsigned long lastRxTime = 0;
bool isReceiving = false;


// ==========================================
// 视频流单帧获取接口 (给 Android App 用的接口)
// ==========================================
httpd_handle_t stream_httpd = NULL;

esp_err_t capture_handler(httpd_req_t *req) {
  camera_fb_t * fb = esp_camera_fb_get();
  
  static int error_count = 0;
  if (!fb) {
    if (error_count++ % 10 == 0) {
      Serial.println("\n🔥 =================================================");
      Serial.println("❌ 抓取画面失败！(摄像头排线可能松动或者没供电)");
      Serial.printf("   3. 内存剩余: Heap=%d bytes, PSRAM=%d bytes\n", ESP.getFreeHeap(), ESP.getFreePsram());
      Serial.println("🔥 =================================================\n");
    }
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }
  
  error_count = 0;

  httpd_resp_set_type(req, "image/jpeg");
  esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
  
  esp_camera_fb_return(fb);
  return res;
}

void startCameraServer() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 81; // 相机推流所在的端口: 81
  
  httpd_uri_t capture_uri = {
    .uri       = "/",
    .method    = HTTP_GET,
    .handler   = capture_handler,
    .user_ctx  = NULL
  };
  
  if (httpd_start(&stream_httpd, &config) == ESP_OK) {
    httpd_register_uri_handler(stream_httpd, &capture_uri);
    Serial.println("📸 图像(JPEG) HTTP API 已启动 -> 端口: 81");
  } else {
    Serial.println("❌ 视频服务器启动失败!");
  }
}


// ==========================================
// 初始化 Setup (代码起点)
// ==========================================
void setup() {
  Serial.begin(115200);
  delay(1500); 
  
  // 启动连接 TOFSense 雷达的内建串口 1 
  Serial2.begin(921600, SERIAL_8N1, TOF_RXD2, TOF_TXD2);

  Serial.println("\n\n==========================================");
  Serial.println("🚀 启动 [双发引擎] ESP32S3-EYE板载视觉 + TOFSense深度");
  Serial.println("==========================================");
  
  // 初始化自带的排线摄像头
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  
  bool camera_ready = false;

  // 必须开启 PSRAM，绝大多数S3-Eye带有8MB PSRAM，支持大分辨率
  if(psramFound()){
    Serial.printf("✨ 检测到外部 PSRAM 芯片！(容量: %d 字节)。\n", ESP.getPsramSize());
    config.frame_size = FRAMESIZE_VGA; // 640x480分辨率
    config.jpeg_quality = 12; // 画质越高数字需要越小 (10-20之间)
    config.fb_count = 2; // 双缓存提速
  } else {
    Serial.println("⚠️ 警告: 未在 IDE 开启 PSRAM! 请在 Tools 菜单打开 PSRAM！降级到极低画质...");
    config.frame_size = FRAMESIZE_QVGA;  
    config.jpeg_quality = 20; 
    config.fb_count = 1; 
  }

  Serial.println("⏳ 正在初始化排线摄像头...");
  
  // 初始化 API
  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("❌ 摄像头初始化失败! (错误码: 0x%x)\n", err);
    Serial.println("   如果是 ESP32S3-EYE 板子，很少初始化失败。请检查排线是否松动或烧死。");
  } else {
    Serial.println("✅ 摄像头初始化完毕！");
    sensor_t * s = esp_camera_sensor_get();
    
    // 如果画面在 App 里是倒着的，可以修改下面两行的数字 0 和 1 (0是正常, 1是翻转)
    s->set_vflip(s, 1);   // 垂直翻转画面
    s->set_hmirror(s, 0); // 水平镜像
    
    camera_ready = true;
  }

  // 建立通信 WiFi 热点
  WiFi.softAP(ssid, password);
  IPAddress IP = WiFi.softAPIP();
  Serial.print("📶 请在安卓手机上连接 WiFi 热点: ");
  Serial.println(ssid);
  Serial.print("🌐 ESP32 主控 IP 地址 (App 的目标): ");
  Serial.println(IP);

  // 启动深度协议栈 TCP 服务器
  tcp_server.begin();
  
  // 无论摄像头是否报错连拍失败，我们强行拉起拍照 API，方便 App 取日志排查
  startCameraServer();
  Serial.println("🔌 TOF 深度数据分发已就绪 -> TCP 端口: 8080");
}

// ==========================================
// ToF 协议解析与发包
// ==========================================
void processAndSendData() {
  bool hasClient = tcp_client.connected();
  
  static int frameCounter = 0;
  bool shouldPrintDebug = (frameCounter++ % 30 == 0); // 防刷屏心跳输出
  
  if (shouldPrintDebug) {
    uint32_t sys_time = (uint32_t)(buffer[4] | (buffer[5] << 8) | (buffer[6] << 16) | (buffer[7] << 24));
    Serial.print("✅ [TOF雷达] 接收正常 | 时间戳: "); Serial.print(sys_time);
    Serial.print(" | 中心距离: ");
  }

  String json = "{\"d\":[";

  for (int i = 0; i < 64; i++) {
    int base = 9 + (i * 6);
    int32_t raw_dis_um = (buffer[base] << 8 | buffer[base+1] << 16 | buffer[base+2] << 24) / 256;
    int32_t real_dis_mm = raw_dis_um / 1000; // 换算为毫米
    
    if (shouldPrintDebug && i == 36) {
      Serial.print(real_dis_mm);
      Serial.println(" mm");
    }

    if (hasClient) {
      json += String(real_dis_mm);
      if (i < 63) json += ","; // JSON 数组逗号隔离
    }
  }
  
  if (hasClient) {
    json += "]}\n"; // JSON 终止符与换行符 (供安卓 readLine 识别)
    tcp_client.print(json); // 推送给安卓 App !
  }
}

// ==========================================
// 主死循环 (一直跑)
// ==========================================
void loop() {
  // 监听有没有 Android 手机连接上了 TCP 8080 端尝试拉取雷达数据
  if (!tcp_client.connected()) {
    tcp_client = tcp_server.available();
    if (tcp_client.connected()) {
      Serial.println("\n✅ [网络] 手机 APP 已成功连入 TOF 管道!\n");
    }
  }

  // ToF 激光雷达防断连检查
  if (millis() - lastRxTime > 2000) {
    if (isReceiving) {
      Serial.println("⚠️ [TOF报警] 超过2秒没收到雷达数据，请检查43、44引脚杜邦线！");
      isReceiving = false;
    }
    lastRxTime = millis();
  }

  // 将物理管脚的二进制高低电平数据高速倒进解包器里寻找 0x57 0x01 包头
  while (Serial2.available() > 0) {
    uint8_t b = Serial2.read();
    if (!isReceiving) {
      Serial.println("📡 [TOF] 信号恢复通畅！");
      isReceiving = true;
    }
    lastRxTime = millis();
    
    if (bufferIndex == 0 && b != 0x57) continue; 
    if (bufferIndex == 1 && b != 0x01) {         
      bufferIndex = 0; 
      continue; 
    }
    
    // 写入缓冲拼图
    buffer[bufferIndex++] = b;
    
    // 凑齐 400 字节一帧
    if (bufferIndex == FRAME_LENGTH) {
      uint8_t checksum = 0;
      for (int i = 0; i < FRAME_LENGTH - 1; i++) {
        checksum += buffer[i];
      }
      
      // 最后一字节是校验码，验证我们收到的包是不是被电磁干扰破坏了
      if (checksum == buffer[FRAME_LENGTH - 1]) {
        processAndSendData(); 
      }
      bufferIndex = 0; // 下一帧从头开始拼图
    }
  }
}
