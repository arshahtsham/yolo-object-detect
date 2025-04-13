# Path to CSV file
$outputFile = "yolov8_memlog.csv"

# Write header if file doesn't exist
if (-not (Test-Path $outputFile)) {
    Add-Content $outputFile "Timestamp,JavaHeap_KB,NativeHeap_KB,TotalPSS_KB,TotalRSS_KB,CPU_Usage_Percent,Available_RAM_KB"
}

while ($true) {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

    # Memory Info
    $output = adb shell dumpsys meminfo com.surendramaran.yolov8tflite 2>&1

    if ($output -match "No process found") {
        Add-Content $outputFile "$timestamp,NOT_RUNNING"
    }
    else {
        $javaHeap = ($output | Select-String "Java Heap:\s+(\d+)").Matches[0].Groups[1].Value
        $nativeHeap = ($output | Select-String "Native Heap:\s+(\d+)").Matches[0].Groups[1].Value
        $totalPss = ($output | Select-String "TOTAL PSS:\s+(\d+)").Matches[0].Groups[1].Value
        $totalRss = ($output | Select-String "TOTAL RSS:\s+(\d+)").Matches[0].Groups[1].Value

        # Get total device available RAM
        $ramOutput = adb shell dumpsys meminfo | Select-String "Free RAM"
        if ($ramOutput) {
            $availableRAM = ($ramOutput -split "\s+")[-2]
        }
        else {
            $availableRAM = "NA"
        }

        # CPU Usage
        $cpuOutput = adb shell top -n 1 | findstr yolov8
        if ($cpuOutput) {
            $cpuUsage = ($cpuOutput -split "\s+") | Where-Object {$_ -match "^\d{1,3}$"} | Select-Object -First 1
        }
        else {
            $cpuUsage = "NOT_FOUND"
        }

        Add-Content $outputFile "$timestamp,$javaHeap,$nativeHeap,$totalPss,$totalRss,$cpuUsage,$availableRAM"
    }

    Start-Sleep -Seconds 1
}
