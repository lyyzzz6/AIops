# 测试聊天接口
$token = "eyJhbGciOiJIUzM4NCJ9.eyJqdGkiOiJmNDhiOGI4Yy0zZGQ2LTRjZGEtYjdkMS1iYjQwZDcxZDBiMzAiLCJzdWIiOiIxIiwidXNlcm5hbWUiOiJhZG1pbiIsInJvbGVzIjpbIlNVUEVSX0FETUlOIl0sImlhdCI6MTc3ODMyNzA3NywiZXhwIjoxNzc4MzM0Mjc3fQ.VYhGmSRBHAU4ouohAs9gI1_y67DgXxIPPYRuVzcvAP32k_pRYyFg0qUO2X2dAzYh"
$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
}
$body = @{
    query = "如何检查服务器CPU使用率？"
} | ConvertTo-Json

Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/chat' -Method POST -Headers $headers -Body $body
