$pairs = @(
    @("RoundedCornerShape(6.dp)", "RoundedCornerShape(8.dp)"),
    @("RoundedCornerShape(10.dp)", "RoundedCornerShape(12.dp)"),
    @("RoundedCornerShape(14.dp)", "RoundedCornerShape(12.dp)"),
    @("RoundedCornerShape(20.dp)", "RoundedCornerShape(24.dp)"),
    @("RoundedCornerShape(28.dp)", "RoundedCornerShape(24.dp)"),
    @("RoundedCornerShape(32.dp)", "RoundedCornerShape(24.dp)")
)
Get-ChildItem -Recurse -Filter *.kt -Path "D:\Doubao\zhuancunmiao\app\src\main\java\com\ashchan\cloudsaver\ui" | ForEach-Object {
    $c = Get-Content -Raw $_.FullName
    $o = $c
    foreach ($p in $pairs) { $c = $c.Replace($p[0], $p[1]) }
    if ($c -ne $o) {
        [System.IO.File]::WriteAllText($_.FullName, $c, (New-Object System.Text.UTF8Encoding($false)))
        "changed " + $_.Name
    }
}
