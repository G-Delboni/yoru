@echo off
rem Yoru: desliga o servidor que o "Iniciar Yoru.bat" deixou em segundo plano.
rem Só encerra o Python do Yoru, nunca outros programas. Acha o servidor de dois jeitos:
rem   1. quem está escutando na porta 8000 (funciona mesmo sem permissão para ler a
rem      linha de comando do processo, que o Windows esconde se o servidor foi ligado
rem      como administrador);
rem   2. processos python com "uvicorn app.main:app" na linha de comando.
chcp 65001 >nul
title Yoru
set "PORTA=8000"
powershell -NoProfile -Command "$ids = @(); $ids += Get-NetTCPConnection -LocalPort %PORTA% -State Listen -ErrorAction SilentlyContinue | ForEach-Object { $_.OwningProcess }; $ids += Get-CimInstance Win32_Process -Filter 'Name=''python.exe''' | Where-Object { $_.CommandLine -like '*uvicorn*app.main:app*' } | ForEach-Object { $_.ProcessId }; $procs = @($ids | Where-Object { $_ -gt 4 } | Sort-Object -Unique | ForEach-Object { Get-CimInstance Win32_Process -Filter ('ProcessId=' + $_) } | Where-Object { $_ -and $_.Name -eq 'python.exe' }); $pais = @($procs | ForEach-Object { Get-CimInstance Win32_Process -Filter ('ProcessId=' + $_.ParentProcessId) } | Where-Object { $_ -and $_.Name -eq 'python.exe' }); $todos = @($procs + $pais | Sort-Object ProcessId -Unique); if (-not $todos) { Write-Host 'O Yoru não estava ligado.'; exit }; $falhou = $false; foreach ($p in $todos) { try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch { if (Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue) { $falhou = $true } } }; Start-Sleep -Milliseconds 500; if ($falhou -or (Get-NetTCPConnection -LocalPort %PORTA% -State Listen -ErrorAction SilentlyContinue)) { Write-Host 'Não consegui desligar o Yoru: ele foi ligado como administrador.'; Write-Host 'Clique com o botão direito em \"Parar Yoru.bat\" e escolha \"Executar como administrador\".' } else { Write-Host 'Yoru desligado.' }"
timeout /t 4 >nul
