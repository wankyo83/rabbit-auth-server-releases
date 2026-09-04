param(
    [Parameter(Mandatory = $true)]
    [string]$Source,

    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$artworkDir = Join-Path $ProjectRoot "artwork"
$resourceRoot = Join-Path $ProjectRoot "app\src\main\res"
New-Item -ItemType Directory -Path $artworkDir -Force | Out-Null

$loaded = [System.Drawing.Bitmap]::FromFile($sourcePath)
$inputBitmap = New-Object System.Drawing.Bitmap($loaded)
$loaded.Dispose()

if ($inputBitmap.Width -ne $inputBitmap.Height) {
    $inputBitmap.Dispose()
    throw "Launcher artwork must be square."
}

$width = $inputBitmap.Width
$height = $inputBitmap.Height
$visited = New-Object 'bool[]' ($width * $height)
$outside = New-Object 'bool[]' ($width * $height)
$queue = [System.Collections.Generic.Queue[int]]::new()

function Add-BackgroundCandidate([int]$x, [int]$y) {
    $index = $y * $width + $x
    if ($visited[$index]) { return }
    $visited[$index] = $true
    $pixel = $inputBitmap.GetPixel($x, $y)
    $largestChannel = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B))
    if ($pixel.A -le 8 -or $largestChannel -le 48) {
        $outside[$index] = $true
        $queue.Enqueue($index)
    }
}

for ($x = 0; $x -lt $width; $x++) {
    Add-BackgroundCandidate $x 0
    Add-BackgroundCandidate $x ($height - 1)
}
for ($y = 0; $y -lt $height; $y++) {
    Add-BackgroundCandidate 0 $y
    Add-BackgroundCandidate ($width - 1) $y
}

while ($queue.Count -gt 0) {
    $index = $queue.Dequeue()
    $x = $index % $width
    $y = [Math]::Floor($index / $width)
    if ($x -gt 0) { Add-BackgroundCandidate ($x - 1) $y }
    if ($x + 1 -lt $width) { Add-BackgroundCandidate ($x + 1) $y }
    if ($y -gt 0) { Add-BackgroundCandidate $x ($y - 1) }
    if ($y + 1 -lt $height) { Add-BackgroundCandidate $x ($y + 1) }
}

$master = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
for ($y = 0; $y -lt $height; $y++) {
    for ($x = 0; $x -lt $width; $x++) {
        $index = $y * $width + $x
        if ($outside[$index]) {
            $master.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
        } else {
            $master.SetPixel($x, $y, $inputBitmap.GetPixel($x, $y))
        }
    }
}
$inputBitmap.Dispose()

$masterPath = Join-Path $artworkDir "ic_launcher-master.png"
$master.Save($masterPath, [System.Drawing.Imaging.ImageFormat]::Png)

$densitySizes = [ordered]@{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

foreach ($entry in $densitySizes.GetEnumerator()) {
    $targetDir = Join-Path $resourceRoot $entry.Key
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    $target = New-Object System.Drawing.Bitmap($entry.Value, $entry.Value, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($target)
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $graphics.DrawImage($master, 0, 0, $entry.Value, $entry.Value)
    $graphics.Dispose()
    $target.Save((Join-Path $targetDir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $target.Dispose()
}

$corner = $master.GetPixel(0, 0)
$master.Dispose()
if ($corner.A -ne 0) {
    throw "The cleaned launcher icon still has an opaque corner."
}

Write-Output "Created a transparent master and five Android launcher densities."
