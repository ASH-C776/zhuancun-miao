$pairs = @(
    @("Spacer(Modifier.width(6.dp))", "Spacer(Modifier.width(8.dp))"),
    @("Spacer(Modifier.width(10.dp))", "Spacer(Modifier.width(12.dp))"),
    @("Spacer(Modifier.width(20.dp))", "Spacer(Modifier.width(16.dp))"),
    @("Spacer(Modifier.height(6.dp))", "Spacer(Modifier.height(8.dp))"),
    @("Spacer(Modifier.height(10.dp))", "Spacer(Modifier.height(12.dp))"),
    @("Spacer(Modifier.height(20.dp))", "Spacer(Modifier.height(24.dp))"),
    @("Spacer(Modifier.height(2.dp))", "Spacer(Modifier.height(4.dp))"),
    @("Spacer(Modifier.height(28.dp))", "Spacer(Modifier.height(24.dp))"),
    @("padding(horizontal = 12.dp, vertical = 3.dp)", "padding(horizontal = 12.dp, vertical = 4.dp)"),
    @("padding(horizontal = 8.dp, vertical = 3.dp)", "padding(horizontal = 8.dp, vertical = 4.dp)"),
    @("modifier.padding(32.dp)", "modifier.padding(24.dp)"),
    @("padding(padding).fillMaxSize().padding(32.dp)", "padding(padding).fillMaxSize().padding(24.dp)"),
    @("Modifier.size(16.dp).padding(top = 1.dp)", "Modifier.size(16.dp).padding(top = 4.dp)")
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
