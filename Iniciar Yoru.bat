@echo off
rem Yoru: liga o servidor com dois cliques.
rem Na primeira vez prepara tudo (ambiente Python, dependências, áreas padrão).
rem O servidor roda em segundo plano, sem janela: pode fechar esta janela à vontade.
rem Ele fica aberto para a rede da casa (o celular sincroniza) até você rodar
rem "Parar Yoru.bat" ou desligar o computador. O que ele escreve vai para data\servidor.log.
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Yoru
set "PORTA=8000"
set "URL=http://127.0.0.1:%PORTA%"

rem Já está ligado? Só abre o navegador.
curl -s -o nul -m 2 "%URL%/api/health" && (
  echo O Yoru já está ligado. Abrindo no navegador...
  start "" "%URL%"
  timeout /t 2 >nul
  exit /b 0
)

rem Primeira vez: cria o ambiente Python e instala as dependências
if not exist ".venv\Scripts\python.exe" (
  echo Preparando o Yoru pela primeira vez. Isso leva um minuto...
  where py >nul 2>nul && (py -3 -m venv .venv) || (python -m venv .venv)
  if not exist ".venv\Scripts\python.exe" (
    echo.
    echo Não encontrei o Python. Instale o Python 3 em https://www.python.org/downloads/
    echo marcando "Add python.exe to PATH", e rode este arquivo de novo.
    pause
    exit /b 1
  )
  ".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -q -r requirements.txt || (
    echo.
    echo Falhou ao instalar as dependências. Verifique a internet e tente de novo.
    pause
    exit /b 1
  )
)

rem Banco novo: cria as áreas padrão
if not exist "data" mkdir data
if not exist "data\yoru.db" ".venv\Scripts\python.exe" seed.py

rem Guarda o log da vez anterior (ajuda a descobrir por que o servidor parou)
if exist "data\servidor.log" move /y "data\servidor.log" "data\servidor.anterior.log" >nul

echo Ligando o Yoru...
rem Inicia o servidor escondido e independente desta janela
powershell -NoProfile -Command "Start-Process -FilePath '.venv\Scripts\python.exe' -ArgumentList '-m','uvicorn','app.main:app','--host','0.0.0.0','--port','%PORTA%' -WorkingDirectory '%CD%' -WindowStyle Hidden -RedirectStandardError 'data\servidor.log' -RedirectStandardOutput 'data\servidor.saida.log'"

rem Espera responder (até 30 s) e abre o navegador
for /l %%i in (1,1,60) do (
  curl -s -o nul -m 1 "%URL%/api/health" && goto ligado
  ping -n 1 -w 500 127.0.0.1 >nul
)
echo.
echo O servidor não respondeu. Veja o motivo em data\servidor.log
pause
exit /b 1

:ligado
start "" "%URL%"
echo.
echo   夜  Yoru ligado em %URL%
echo      Roda em segundo plano: pode fechar esta janela.
echo      Para desligar, use "Parar Yoru.bat".
timeout /t 5 >nul
exit /b 0
