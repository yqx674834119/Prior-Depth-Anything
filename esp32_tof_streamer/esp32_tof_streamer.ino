#include <WiFi.h>
#include <WiFiUdp.h>

// WiFi AP Setup
const char *ssid = "ESP32_ToF_Scanner";
const char *password = "88888888"; // Min 8 chars for WPA2

// UDP Setup
WiFiUDP udp;
const int localUdpPort = 12345;
const int clientUdpPort = 12346;

// Phone IP will be saved once the phone sends a "hello" packet
IPAddress phoneIP;
bool isPhoneConnected = false;

// Simulated ToF Resolution (e.g. 8x8 VL53L5CX, or 64x64 if complex)
// Let's use 64 float distances for 8x8 matrix to demonstrate
const int MATRIX_SIZE = 64; 
float depthMatrix[MATRIX_SIZE];

void setup() {
  Serial.begin(115200);
  
  // Set ESP32 as an Access Point
  Serial.println("Configuring access point...");
  WiFi.softAP(ssid, password);
  
  IPAddress myIP = WiFi.softAPIP();
  Serial.print("AP IP address: ");
  Serial.println(myIP);
  
  // Start UDP
  udp.begin(localUdpPort);
  Serial.printf("Now listening at IP %s, UDP port %d\n", myIP.toString().c_str(), localUdpPort);
}

void loop() {
  // 1. Check if we received any packet from the phone
  int packetSize = udp.parsePacket();
  if (packetSize) {
    // Read the packet
    char incomingPacket[255];
    int len = udp.read(incomingPacket, 255);
    if (len > 0) {
      incomingPacket[len] = 0; // Null-terminate
    }
    
    Serial.printf("Received UDP packet from %s:%d\n", udp.remoteIP().toString().c_str(), udp.remotePort());
    Serial.printf("Message: %s\n", incomingPacket);
    
    String msg = String(incomingPacket);
    msg.trim();
    
    if (msg.equalsIgnoreCase("connect") || msg.equalsIgnoreCase("hello")) {
      phoneIP = udp.remoteIP();
      isPhoneConnected = true;
      Serial.println("Phone connected! Starting ToF stream.");
      
      // Reply to acknowledge
      udp.beginPacket(phoneIP, clientUdpPort);
      udp.print("ESP32_ACK");
      udp.endPacket();
    } else if (msg.equalsIgnoreCase("disconnect")) {
      isPhoneConnected = false;
      Serial.println("Phone disconnected! Stopping stream.");
    }
  }

  // 2. Stream Simulated ToF data to the phone if connected
  if (isPhoneConnected) {
    // Generate some fake sine-wave/noise depth data to simulate a moving sensor scanning
    for(int i = 0; i < MATRIX_SIZE; i++) {
        depthMatrix[i] = 1.0f + 0.5f * sin(millis() / 1000.0f + i) + random(0, 100)/1000.0f;
    }
    
    // Send binary byte array
    udp.beginPacket(phoneIP, clientUdpPort);
    udp.write((uint8_t*)depthMatrix, sizeof(depthMatrix));
    udp.endPacket();
    
    delay(50); // Steam at roughly 20 FPS (50ms interval)
  }
}
