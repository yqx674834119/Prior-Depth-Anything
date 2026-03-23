// ==========================================
// 板型：GOOUUU ESP32-S3-CAM (果云科技开发板)
// 摄像头：OV2640 自带排线
// 引脚：已根据官方引脚图100%核实
// ==========================================
#include <WiFi.h>
#include <WiFiClient.h>
#include "esp_camera.h"
#include "esp_http_server.h"

// ==========================================
// WiFi 热点设置 (手机连这个WiFi)
// ==========================================
const char* ssid     = "ToFSense_AP";
const char* password = "password123";

WiFiServer tcp_server(8080);
WiFiClient tcp_client;

// ==========================================
// GOOUUU ESP32-S3-CAM 摄像头引脚
// 来源：官方硬件引脚图 + 电路图，和 CAMERA_MODEL_ESP32S3_EYE 引脚一致
// ==========================================
#define PWDN_GPIO_NUM  -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM  15
#define SIOD_GPIO_NUM   4  // CAM_SIOD
#define SIOC_GPIO_NUM   5  // CAM_SIOC

#define Y9_GPIO_NUM    16  // CAM_Y9  (D7)
#define Y8_GPIO_NUM    17  // CAM_Y8  (D6)
#define Y7_GPIO_NUM    18  // CAM_Y7  (D5)
#define Y6_GPIO_NUM    12  // CAM_Y6  (D4)
#define Y5_GPIO_NUM    10  // CAM_Y5  (D3)
#define Y4_GPIO_NUM     8  // CAM_Y4  (D2)
#define Y3_GPIO_NUM     9  // CAM_Y3  (D1)
#define Y2_GPIO_NUM    11  // CAM_Y2  (D0)

#define VSYNC_GPIO_NUM  6  // CAM_VYSNC
#define HREF_GPIO_NUM   7  // CAM_HREF
#define PCLK_GPIO_NUM  13  // CAM_PCLK

// ==========================================
// TOFSense 激光雷达引脚 (接线方式：IO47接TOF TX，IO48接TOF RX)
// ==========================================
#define TOF_RXD2 47
#define TOF_TXD2 48

// ==========================================
// ToF 数据缓存
// ==========================================
#define FRAME_LENGTH 400
uint8_t buffer[FRAME_LENGTH];
int bufferIndex = 0;
unsigned long lastRxTime = 0;
bool isReceiving = false;

// ==========================================
// HTTP 单帧图像服务器
// ==========================================
httpd_handle_t stream_httpd = NULL;

esp_err_t capture_handler(httpd_req_t *req) {
  camera_fb_t * fb = esp_camera_fb_get();

  static int error_count = 0;
  if (!fb) {
    if (error_count++ % 20 == 0) {
      Serial.printf("\n❌ [CAMERA] 取帧失败! (排线松动/接触不良?) Heap=%d PSRAM=%d\n",
                    ESP.getFreeHeap(), ESP.getFreePsram());
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
  config.server_port = 81;

  httpd_uri_t capture_uri = {
    .uri       = "/",
    .method    = HTTP_GET,
    .handler   = capture_handler,
    .user_ctx  = NULL
  };

  if (httpd_start(&stream_httpd, &config) == ESP_OK) {
    httpd_register_uri_handler(stream_httpd, &capture_uri);
    Serial.println("📸 图像 HTTP API 已就绪 -> 端口: 81");
  } else {
    Serial.println("❌ 图像 HTTP 服务器启动失败!");
  }
}

// ==========================================
// 初始化
// ==========================================
void setup() {
  Serial.begin(115200);
  Serial.setDebugOutput(true); // 打开乐鑫底层调试信息，方便排查摄像头问题
  delay(1500);

  // TOFSense 串口初始化
  Serial2.begin(921600, SERIAL_8N1, TOF_RXD2, TOF_TXD2);

  Serial.println("\n==========================================");
  Serial.println("🚀 GOOUUU ESP32-S3-CAM + TOFSense 双引擎启动");
  Serial.println("==========================================");

  // ==========================================
  // 摄像头配置
  // 完全参照 Sketch_07.1_CameraWebServer 的工厂验证初始化流程
  // ==========================================
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;

  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;

  config.pin_xclk    = XCLK_GPIO_NUM;
  config.pin_pclk    = PCLK_GPIO_NUM;
  config.pin_vsync   = VSYNC_GPIO_NUM;
  config.pin_href    = HREF_GPIO_NUM;

  // 注意：这两行的参数名必须是 pin_sscb_sda / pin_sscb_scl (不是sccb)
  config.pin_sscb_sda = SIOD_GPIO_NUM;
  config.pin_sscb_scl = SIOC_GPIO_NUM;

  config.pin_pwdn  = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;

  config.xclk_freq_hz = 20000000;

  // 先设置为 UXGA 和 JPEG，后续 PSRAM 存在时保留高分辨率
  config.frame_size   = FRAMESIZE_UXGA;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode    = CAMERA_GRAB_WHEN_EMPTY;
  config.fb_location  = CAMERA_FB_IN_PSRAM;
  config.jpeg_quality = 12;
  config.fb_count     = 1;

  // 如果检测到 PSRAM，开启高画质双缓存
  if (psramFound()) {
    Serial.printf("✨ 检测到 PSRAM (%d bytes)，开启高画质双缓存\n", ESP.getPsramSize());
    config.jpeg_quality = 10;
    config.fb_count     = 2;
    config.grab_mode    = CAMERA_GRAB_LATEST;
  } else {
    // 没有 PSRAM 时降级到较低分辨率
    Serial.println("⚠️ 未检测到 PSRAM，使用低画质 SVGA 模式");
    config.frame_size  = FRAMESIZE_SVGA;
    config.fb_location = CAMERA_FB_IN_DRAM;
  }

  Serial.println("⏳ 正在初始化 OV2640 摄像头...");
  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("❌ 摄像头初始化失败! 错误码: 0x%x\n", err);
    if (err == 0x106) {
      Serial.println("   -> 0x106 表示无法通过 I2C 识别传感器:");
      Serial.println("   -> 1. 请检查排线是否完全插紧 (最常见原因!)");
      Serial.println("   -> 2. 请确认 Arduino IDE Tools -> PSRAM 选了 OPI PSRAM");
      Serial.println("   -> 3. 请确认 Tools -> Flash Size 选了 4MB (<board name>)");
    }
    // 即使摄像头失败，仍然启动 WiFi 和 TOF，方便继续调试
  } else {
    Serial.println("✅ OV2640 摄像头初始化成功!");
    sensor_t * s = esp_camera_sensor_get();
    s->set_vflip(s, 1);      // 垂直翻转 (根据实际效果可改为 0)
    s->set_brightness(s, 1); // 轻微提亮
    s->set_saturation(s, 0); // 降低饱和度
  }

  // ==========================================
  // 建立 WiFi 热点
  // ==========================================
  WiFi.softAP(ssid, password);
  IPAddress IP = WiFi.softAPIP();
  Serial.printf("📶 WiFi 热点已开启: %s\n", ssid);
  Serial.printf("🌐 IP 地址: %s\n", IP.toString().c_str());

  // ==========================================
  // 启动服务器
  // ==========================================
  tcp_server.begin();
  startCameraServer();
  Serial.println("🔌 TOF TCP 服务器已就绪 -> 端口: 8080");
  Serial.println("==========================================");
  Serial.println("✅ 初始化完毕！请连接手机 WiFi 并打开 App");
  Serial.println("==========================================\n");
}

// ==========================================
// ToF 数据解析与发送
// ==========================================
void processAndSendData() {
  bool hasClient = tcp_client.connected();
  static int frameCounter = 0;
  bool shouldPrint = (frameCounter++ % 30 == 0);

  if (shouldPrint) {
    uint32_t sys_time = (uint32_t)(buffer[4] | (buffer[5] << 8) | (buffer[6] << 16) | (buffer[7] << 24));
    Serial.printf("✅ [TOF] 时间戳: %d ms", sys_time);
  }

  String json = "{\"d\":[";
  for (int i = 0; i < 64; i++) {
    int base = 9 + (i * 6);
    int32_t raw_dis_um = (buffer[base] << 8 | buffer[base+1] << 16 | buffer[base+2] << 24) / 256;
    int32_t real_dis_mm = raw_dis_um / 1000;

    if (shouldPrint && i == 36) {
      Serial.printf(" | 中心距离: %d mm\n", real_dis_mm);
    }

    if (hasClient) {
      json += String(real_dis_mm);
      if (i < 63) json += ",";
    }
  }

  if (hasClient) {
    json += "]}\n";
    tcp_client.print(json);
  }
}

// ==========================================
// 主循环
// ==========================================
void loop() {
  // 接受 TOF TCP 连接
  if (!tcp_client.connected()) {
    tcp_client = tcp_server.available();
    if (tcp_client.connected()) {
      Serial.println("\n✅ [网络] 手机 App 已接入 TOF 数据通道!\n");
    }
  }

  // TOF 超时警告
  if (millis() - lastRxTime > 2000) {
    if (isReceiving) {
      Serial.println("⚠️ [TOF] 超2秒无数据，检查 IO47/IO48 杜邦线!");
      isReceiving = false;
    }
    lastRxTime = millis();
  }

  // 读取 TOF 串口数据并解析帧
  while (Serial2.available() > 0) {
    uint8_t b = Serial2.read();
    if (!isReceiving) {
      Serial.println("📡 [TOF] 信号恢复！");
      isReceiving = true;
    }
    lastRxTime = millis();

    if (bufferIndex == 0 && b != 0x57) continue;
    if (bufferIndex == 1 && b != 0x01) { bufferIndex = 0; continue; }

    buffer[bufferIndex++] = b;

    if (bufferIndex == FRAME_LENGTH) {
      uint8_t checksum = 0;
      for (int i = 0; i < FRAME_LENGTH - 1; i++) checksum += buffer[i];
      if (checksum == buffer[FRAME_LENGTH - 1]) {
        processAndSendData();
      }
      bufferIndex = 0;
    }
  }
}
