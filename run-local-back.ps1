Get-Content .\confs\.env | ForEach-Object {
  if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }

  $name, $value = $_ -split '=', 2

  if ($name -and $value) {
    $name = $name.Trim()
    $value = $value.Trim().Trim('"').Trim("'")
    [Environment]::SetEnvironmentVariable($name, $value, "Process")
  }
}

mvn spring-boot:run