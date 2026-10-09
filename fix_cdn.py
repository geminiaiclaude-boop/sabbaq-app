import os

with open('admin/index.html', 'r', encoding='utf-8') as f:
    content = f.read()

old_script = '<script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.4"></script>'
new_script = '<script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.4/dist/umd/supabase.min.js" integrity="sha384-GFr3yTh5lJznCbZfpTtXnwboFsxqtTQoeTZCRHhE0579KrRmlCzen5AA8ohaB5ug" crossorigin="anonymous"></script>'

content = content.replace(old_script, new_script)

with open('admin/index.html', 'w', encoding='utf-8') as f:
    f.write(content)
