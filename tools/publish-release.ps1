# 一键双端发版：构建 release APK → GitHub Release + Gitee Release（同一份 APK）
#
# 用法：
#   powershell -File tools\publish-release.ps1 -Notes "本次更新说明（markdown）"
#   可选 -SkipBuild 复用已有 app-release.apk
#
# 令牌来源（任选其一，读不到就报错退出）：
#   1) 环境变量 GITHUB_TOKEN / GITEE_TOKEN
#   2) ~/.git-credentials 里存的 https://user:token@github.com、https://user:token@gitee.com 条目
#
# 约定（与客户端 UpdateRules 强绑定，别改）：
#   - tag 用 vX.Y.Z（三段数字），name 与 tag 同名
#   - prerelease / draft 必须 false
#   - 必须上传 .apk 附件；GitHub 与 Gitee 传的是同一个文件
#   - Gitee 建 Release 的参数只能走 JSON body + query 里的 access_token（放 URL query 的 tag_name 也行，但 body 里别放 access_token）

param(
    [Parameter(Mandatory = $false)][string]$Notes = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$GithubRepo = "ASH-C776/zhuancun-miao"
$GiteeRepo = "kijeye/zhuancun-miao"

function Get-Token([string]$hostName, [string]$envName, [string]$preferUser) {
    if ((Get-Item "env:$envName" -ErrorAction SilentlyContinue).Value) { return (Get-Item "env:$envName").Value }
    $credFile = Join-Path $env:USERPROFILE ".git-credentials"
    if (Test-Path $credFile) {
        $found = @()
        foreach ($line in Get-Content $credFile) {
            if ($line -match "^https://([^@]+)@$([regex]::Escape($hostName))$") {
                $cred = $matches[1]
                if ($cred -match "^([^:]+):(.+)$") { $found += [pscustomobject]@{ user = $matches[1]; token = $matches[2] } }
            }
        }
        # 同一站点可能存了多份（手机号密码 / 用户名令牌），优先挑指定用户名那条
        $pick = $found | Where-Object { $_.user -eq $preferUser } | Select-Object -First 1
        if (-not $pick) { $pick = $found | Select-Object -First 1 }
        if ($pick) { return $pick.token }
    }
    throw "找不到 $hostName 的令牌：请设置环境变量 $envName 或写进 ~/.git-credentials"
}

# 从 build.gradle.kts 读版本号（客户端 UpdateRules 用同一公式）
$gradleFile = Join-Path $repoRoot "app\build.gradle.kts"
$versionName = (Select-String -LiteralPath $gradleFile -Pattern 'val appVersionName = "([^"]+)"').Matches[0].Groups[1].Value
if (-not $versionName) { throw "没能从 app/build.gradle.kts 读到 appVersionName" }
$parts = $versionName.Split(".")
$versionCode = [int]$parts[0] * 10000 + [int]$parts[1] * 100 + [int]$parts[2]
$tag = "v$versionName"
Write-Host "版本：$tag（versionCode=$versionCode）"

if (-not $SkipBuild) {
    Write-Host "构建 release APK…"
    $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
    $env:GRADLE_USER_HOME = "D:/Android/.gradle"
    & "D:\Android\gradle-8.14.5\bin\gradle.bat" -p $repoRoot :app:assembleRelease --no-daemon -q
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleRelease 失败" }
}

$apkSrc = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkSrc)) { throw "找不到 $apkSrc" }
$apkName = "zhuancun-miao-$tag.apk"
$apkOut = Join-Path (Split-Path $apkSrc) $apkName
Copy-Item -LiteralPath $apkSrc -Destination $apkOut -Force
$size = (Get-Item $apkOut).Length
Write-Host "APK：$apkName（$size 字节）"

if (-not $Notes) { $Notes = "## $tag`n`n详见 CHANGELOG.md" }

# ---------- GitHub ----------
$ghToken = Get-Token "github.com" "GITHUB_TOKEN" "ASH-C776"
$ghHeaders = @{ Authorization = "token $ghToken"; "User-Agent" = "zhuancun-miao-publish"; "Accept" = "application/vnd.github+json" }
$ghBody = @{ tag_name = $tag; name = $tag; body = $Notes; draft = $false; prerelease = $false } | ConvertTo-Json -Depth 3
$ghRel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$GithubRepo/releases" -Headers $ghHeaders -Body $ghBody -ContentType "application/json" -TimeoutSec 60
$ghAsset = Invoke-RestMethod -Method Post -Uri "https://uploads.github.com/repos/$GithubRepo/releases/$($ghRel.id)/assets?name=$apkName" -Headers @{ Authorization = "token $ghToken"; "User-Agent" = "zhuancun-miao-publish"; "Content-Type" = "application/vnd.android.package-archive" } -InFile $apkOut -TimeoutSec 300
Write-Host "GitHub：$($ghRel.html_url)  附件 $($ghAsset.name)"

# ---------- Gitee ----------
$giteeToken = Get-Token "gitee.com" "GITEE_TOKEN" "kijeye"
$giteeApi = "https://gitee.com/api/v5/repos/$GiteeRepo"
$giteeBody = @{ access_token = $giteeToken; tag_name = $tag; name = $tag; target_commitish = "dev"; body = $Notes; prerelease = "false"; draft = "false" } | ConvertTo-Json -Compress
$giteeRel = Invoke-RestMethod -Method Post -Uri "$giteeApi/releases" -Body $giteeBody -ContentType "application/json" -TimeoutSec 60
$giteeAsset = Invoke-RestMethod -Method Post -Uri "$giteeApi/releases/$($giteeRel.id)/attach_files?access_token=$giteeToken" -Form @{ file = Get-Item $apkOut } -TimeoutSec 300
Write-Host "Gitee ：https://gitee.com/$GiteeRepo/releases/tag/$tag  附件 $($giteeAsset.name)（$($giteeAsset.size) 字节）"

Write-Host "双端发布完成：$tag"
