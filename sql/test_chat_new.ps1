# 测试聊天接口 - 新问题
$token = "eyJhbGciOiJIUzM4NCJ9.eyJqdGkiOiI5Y2RjZjhiMC02MWQwLTQ4YzQtODY4Yi1iYzM5ZjgzNWM3MmMiLCJzdWIiOiIxIiwidXNlcm5hbWUiOiJhZG1pbiIsInJvbGVzIjpbIlNVUEVSX0FETUlOIl0sImlhdCI6MTc3ODMzMjY2OCwiZXhwIjoxNzc4MzM5ODY4fQ.HZrb5RG28MM-VLZu9v_7FYghfhfMrykadoXLZGpATWOwtARC303tg3r4Ncn3MhLg"
$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
}
$body = @{
    query = "如何排查服务器内存泄漏问题？"
} | ConvertTo-Json

Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/chat' -Method POST -Headers $headers -Body $body
