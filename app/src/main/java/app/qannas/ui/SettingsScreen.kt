package app.qannas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.qannas.Settings
import app.qannas.core.Filters
import app.qannas.network.SupabaseClient
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenA11y: () -> Unit, onToggleKeeper: (Boolean) -> Unit) {
    var filters by remember { mutableStateOf(Settings.filters) }
    var isActive by remember { mutableStateOf(Settings.isActive) }
    var activationCode by remember { mutableStateOf("") }
    var codeMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("إعدادات قناص الطلبات", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = filters.minFare ?: "",
            onValueChange = { 
                filters = filters.copy(minFare = it)
                Settings.filters = filters 
            },
            label = { Text("الحد الأدنى للسعر (مثال: 10)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = filters.maxPickupMinutes?.toString() ?: "",
            onValueChange = { 
                filters = filters.copy(maxPickupMinutes = it.toIntOrNull())
                Settings.filters = filters 
            },
            label = { Text("أقصى مسافة للراكب (بالدقائق)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("تشغيل القناص", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = isActive,
                onCheckedChange = { 
                    isActive = it
                    Settings.isActive = it
                    onToggleKeeper(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenA11y, modifier = Modifier.fillMaxWidth()) {
            Text("تفعيل صلاحية الوصول (Accessibility)")
        }

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        Text("تجديد الاشتراك", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = activationCode,
                onValueChange = { activationCode = it.uppercase() },
                label = { Text("كود التفعيل (مثال: QAN-XXXX)") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    codeMessage = "جاري التحقق..."
                    val res = SupabaseClient.redeemCode(activationCode)
                    if(res.isSuccess) {
                        codeMessage = "تم تفعيل الاشتراك بنجاح!"
                        activationCode = ""
                    } else {
                        codeMessage = res.exceptionOrNull()?.message ?: "حدث خطأ"
                    }
                }
            }) {
                Text("تفعيل")
            }
        }
        if (codeMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(codeMessage, color = if (codeMessage.contains("نجاح")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}
