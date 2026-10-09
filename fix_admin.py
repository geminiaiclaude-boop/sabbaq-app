import os

with open('admin/index.html', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("const supabase = supabase.createClient", "const db = window.supabase.createClient")
content = content.replace("supabase.auth.", "db.auth.")
content = content.replace("supabase.from(", "db.from(")
content = content.replace("supabase.rpc(", "db.rpc(")

# Fix resetDevice to use RPC
old_reset = "await supabase.from('drivers').update({ device_id: null }).eq('id', id);"
new_reset = "await db.rpc('admin_reset_device', { p_driver_id: id });"
content = content.replace(old_reset, new_reset)

with open('admin/index.html', 'w', encoding='utf-8') as f:
    f.write(content)
