# Path to CSV file
$outputFile = "yolov8_memlog.csv"

# Write header if file doesn't exist
if (-not (Test-Path $outputFile)) {
    Add-Content $outputFile "Timestamp,JavaHeap_KB,NativeHeap_KB,TotalPSS_KB,TotalRSS_KB"
}

while ($true) {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

    # Run adb command and get output
    $output = adb shell dumpsys meminfo com.surendramaran.yolov8tflite 2>&1

    if ($output -match "No process found") {
        Add-Content $outputFile "$timestamp,NOT_RUNNING"
    }
    else {
        # Extract values using regex
        $javaHeap = ($output | Select-String "Java Heap:\s+(\d+)").Matches[0].Groups[1].Value
        $nativeHeap = ($output | Select-String "Native Heap:\s+(\d+)").Matches[0].Groups[1].Value
        $totalPss = ($output | Select-String "TOTAL PSS:\s+(\d+)").Matches[0].Groups[1].Value
        $totalRss = ($output | Select-String "TOTAL RSS:\s+(\d+)").Matches[0].Groups[1].Value

        # Log to CSV
        Add-Content $outputFile "$timestamp,$javaHeap,$nativeHeap,$totalPss,$totalRss"
    }

    Start-Sleep -Seconds 1
}
