$ErrorActionPreference = "Stop"
$root = "D:\Doubao\zhuancunmiao\app\src\main\java\com\ashchan\cloudsaver\ui"
$script:map = @{ 2 = 4; 6 = 8; 10 = 12; 14 = 16; 18 = 16; 20 = 16; 28 = 24 }

$padEval = {
    param($m)
    $inner = $m.Groups[1].Value
    # 逐个把 padding 参数里的数字按映射改掉
    [regex]::Replace($inner, '(\d+)\.dp', {
            param($n)
            $v = [int]$n.Groups[1].Value
            if ($script:map.ContainsKey($v)) { "$($script:map[$v]).dp" } else { "$v.dp" }
        })
}
$bareEval = {
    param($m)
    $v = [int]$m.Groups[1].Value
    if ($script:map.ContainsKey($v)) { "$($script:map[$v]).dp" } else { "$v.dp" }
}

Get-ChildItem -Recurse -Filter *.kt -Path $root | ForEach-Object {
    $raw = Get-Content -Raw $_.FullName
    $new = [regex]::Replace($raw, '(?<![A-Za-z])padding\(([^)]*)\)', {
            param($m)
            # padding 的实参是「padding」这种变量名时保持原样
            if ($m.Groups[1].Value -notmatch '\d+\.dp') { return $m.Value }
            "padding(" + [regex]::Replace($m.Groups[1].Value, '(\d+)\.dp', {
                    param($n)
                    $v = [int]$n.Groups[1].Value
                    if ($script:map.ContainsKey($v)) { "$($script:map[$v]).dp" } else { "$v.dp" }
                }) + ")"
        })
    $new = [regex]::Replace($new, 'spacedBy\((\d+)\.dp\)', {
            param($m)
            $v = [int]$m.Groups[1].Value
            if ($script:map.ContainsKey($v)) { "spacedBy($($script:map[$v]).dp)" } else { $m.Value }
        })
    $new = [regex]::Replace($new, "Spacer(Modifier\.width\((\d+)\.dp)", {
            param($m)
            $v = [int]$m.Groups[1].Value
            if ($script:map.ContainsKey($v)) { "Spacer(Modifier.width($($script:map[$v]).dp)" } else { $m.Value }
        })
    $new = [regex]::Replace($new, "Spacer(Modifier\.height\((\d+)\.dp)", {
            param($m)
            $v = [int]$m.Groups[1].Value
            if ($script:map.ContainsKey($v)) { "Spacer(Modifier.height($($script:map[$v]).dp)" } else { $m.Value }
        })
    if ($new -ne $raw) {
        [System.IO.File]::WriteAllText($_.FullName, $new, (New-Object System.Text.UTF8Encoding($false)))
        "changed: " + $_.Name
    }
}
