import os

with open('app/src/main/java/app/qannas/network/SupabaseClient.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the PATCH block
old_block = """val patchPayload = buildJsonObject { put("device_id", deviceId) }.toString().toRequestBody(jsonMediaType)
                        val patchReq = Request.Builder()
                            .url("${BuildConfig.SUPABASE_URL}/rest/v1/drivers")
                            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .header("Authorization", "Bearer $token")
                            .patch(patchPayload)
                            .build()
                        client.newCall(patchReq).execute()"""

new_block = """val bindPayload = buildJsonObject { put("p_device_id", deviceId) }.toString().toRequestBody(jsonMediaType)
                        val bindReq = Request.Builder()
                            .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/bind_device")
                            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .header("Authorization", "Bearer $token")
                            .post(bindPayload)
                            .build()
                        val bindRes = client.newCall(bindReq).execute()
                        if (!bindRes.isSuccessful) {
                            return@withContext Result.failure(Exception("Failed to bind device"))
                        }"""

content = content.replace(old_block, new_block)

with open('app/src/main/java/app/qannas/network/SupabaseClient.kt', 'w', encoding='utf-8') as f:
    f.write(content)
