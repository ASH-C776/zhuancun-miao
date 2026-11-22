param(
    [string]$PwdId = '225348afbc93',
    [string]$Passcode = '',
    [string]$PdirFid = '',
    [switch]$Raw
)
$ErrorActionPreference = 'Stop'
$base = 'https://drive-pc.quark.cn'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/3.14.2 Chrome/112.0.5615.165 Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch'
$headers = @{
    'content-type' = 'application/json'
    'user-agent'   = $ua
    'accept'       = 'application/json, text/plain, */*'
    'referer'      = 'https://pan.quark.cn/'
}

$body = @{ pwd_id = $PwdId; passcode = $Passcode } | ConvertTo-Json -Compress
$tok = Invoke-RestMethod -Uri "$base/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc" -Method Post -Headers $headers -Body $body
'TOKEN ' + ($tok | ConvertTo-Json -Depth 5 -Compress)

$stoken = $tok.data.stoken
if (-not $stoken) { throw 'no stoken' }

$q = [ordered]@{
    pr                    = 'ucpro'
    fr                    = 'pc'
    pwd_id                = $PwdId
    stoken                = $stoken
    pdir_fid              = $PdirFid
    force                 = '0'
    _page                 = '1'
    _size                 = '50'
    _fetch_banner         = '0'
    _fetch_share          = '0'
    _fetch_total          = '1'
    _sort                 = 'file_type:asc,updated_at:desc'
    ver                   = '2'
    fetch_share_full_path = '0'
}
$query = ($q.GetEnumerator() | ForEach-Object { "$($_.Key)=$([uri]::EscapeDataString([string]$_.Value))" }) -join '&'
$resp = Invoke-RestMethod -Uri "$base/1/clouddrive/share/sharepage/detail?$query" -Method Get -Headers $headers

if ($Raw) {
    'RAW ' + ($resp | ConvertTo-Json -Depth 8 -Compress)
} else {
    'TOTAL ' + $resp.data.metadata._total
    $resp.data.list | ForEach-Object { '  ' + $_.file_name + ' | dir=' + $_.dir + ' | fid=' + $_.fid }
}
