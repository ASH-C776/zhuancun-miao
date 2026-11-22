param(
    [string]$ShareId = '1962143988912746595_aeWw8Ru726g3wcPx',
    [string]$Code = '',
    [string]$ParentId = '',
    [switch]$Raw
)
$ErrorActionPreference = 'Stop'
$base = 'https://api.guangyapan.com/'

function NewHex([int]$n) {
    -join ((1..$n) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
}

$headers = @{
    'accept'       = 'application/json, text/plain, */*'
    'content-type' = 'application/json'
    'did'          = (NewHex 16)
    'dt'           = '4'
    'origin'       = 'https://www.guangyapan.com'
    'referer'      = 'https://www.guangyapan.com/'
    'traceparent'  = ('00-' + (NewHex 32) + '-' + (NewHex 16) + '-01')
    'user-agent'   = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36'
}

function Post([string]$path, $obj) {
    Invoke-RestMethod -Uri ($base + $path) -Method Post -Headers $headers -Body ($obj | ConvertTo-Json -Compress -Depth 5)
}

$summary = Post 'nd.bizuserres.s/v1/get_share_summary' @{ shareId = $ShareId }
'SUMMARY ' + ($summary | ConvertTo-Json -Depth 4 -Compress)

$tok = Post 'nd.bizuserres.s/v1/get_share_access_token' @{ shareId = $ShareId; code = $Code }
'TOKEN ' + ($tok | ConvertTo-Json -Depth 4 -Compress)

$accessToken = $tok.data.accessToken
if (-not $accessToken) { $accessToken = $tok.data.access_token }
if (-not $accessToken) { $accessToken = $tok.access_token }
if (-not $accessToken) { $accessToken = $tok.data.token }
if (-not $accessToken) { $accessToken = $tok.token }
"ACCESS_TOKEN_LEN " + $accessToken.Length

$resp = Post 'nd.bizuserres.s/v1/get_share_page_files_list' @{
    accessToken = $accessToken
    parentId    = $ParentId
    page        = 1
    pageSize    = 50
    orderBy     = 0
    sortType    = 0
}
if ($Raw) {
    'RAW ' + ($resp | ConvertTo-Json -Depth 8 -Compress)
} else {
    'RESP ' + ($resp | ConvertTo-Json -Depth 6 -Compress)
}
