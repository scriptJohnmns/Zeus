# zeus

```
███████╗███████╗██╗   ██╗███████╗
╚══███╔╝██╔════╝██║   ██║██╔════╝
  ███╔╝ █████╗  ██║   ██║███████╗
 ███╔╝  ██╔══╝  ██║   ██║╚════██║
███████╗███████╗╚██████╔╝███████║
╚══════╝╚══════╝ ╚═════╝ ╚══════╝
```

**Painel e controle de carga da bateria para Linux, direto no terminal.**

O zeus lê e grava os *charge thresholds* (limites de carga) da bateria através do `sysfs` do kernel. Com ele você deixa o notebook na tomada travado em 80%, o que prolonga a vida útil da bateria, e libera até 100% quando for sair. É um único script [Babashka](https://babashka.org): sem daemon, sem serviço rodando em segundo plano e sem dependências além do próprio `bb`.

---

## Compatibilidade

> **Testado apenas em um ThinkPad T14 Gen 5 (Intel Core Ultra 5 135U), rodando Fedora.**

Ele deve funcionar em qualquer notebook cujo kernel exponha estes arquivos em `/sys/class/power_supply/BAT0/`:

- `charge_control_start_threshold`
- `charge_control_end_threshold`
- `charge_behaviour` (opcional)

Em geral isso vale para ThinkPads com o driver `thinkpad_acpi` e para alguns modelos de outros fabricantes, mas **nada disso foi verificado** fora da máquina acima. Se você testar em outro notebook, abra uma issue contando se funcionou ou não.

Para conferir se a sua máquina expõe esses arquivos:

```sh
ls /sys/class/power_supply/BAT*/charge_control_*
```

Se aparecerem os dois arquivos `charge_control_*`, o zeus deve funcionar. Se a sua bateria se chamar `BAT1` em vez de `BAT0`, veja a seção [Configuração](#configuração).

---

## Instalação

### Requisitos

- Linux com os arquivos de threshold no sysfs (veja acima)
- [Babashka](https://github.com/babashka/babashka#installation) (`bb`)
- `sudo`, para gravar os thresholds

### 1. Instale o Babashka

```sh
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```

Também dá para instalar pelo Homebrew, Nix, Scoop e outros. Veja as opções na [página do Babashka](https://github.com/babashka/babashka#installation). Confira se deu certo com:

```sh
bb --version
```

### 2. Instale o zeus

**Opção A: clonando o repositório** (recomendado, facilita atualizar depois)

```sh
git clone https://github.com/SEU_USUARIO/zeus.git ~/.local/share/zeus
mkdir -p ~/.local/bin
ln -sf ~/.local/share/zeus/zeus.bb ~/.local/bin/zeus
chmod +x ~/.local/share/zeus/zeus.bb
```

Para atualizar no futuro:

```sh
git -C ~/.local/share/zeus pull
```

**Opção B: baixando só o script**

```sh
mkdir -p ~/.local/bin
curl -fsSL https://raw.githubusercontent.com/SEU_USUARIO/zeus/main/zeus.bb -o ~/.local/bin/zeus
chmod +x ~/.local/bin/zeus
```

### 3. Garanta que `~/.local/bin` está no PATH

A maioria das distros (Fedora, Ubuntu, etc.) já inclui esse diretório. Se o comando `zeus` não for encontrado, adicione ao seu `~/.bashrc` ou `~/.zshrc`:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

Abra um novo terminal e rode:

```sh
zeus
```

### Desinstalação

```sh
rm ~/.local/bin/zeus
rm -rf ~/.local/share/zeus ~/.cache/zeus
```

Os thresholds gravados continuam valendo depois de desinstalar. Para voltar ao comportamento de fábrica, rode `zeus 100` antes de remover.

---

## Uso

```sh
zeus              # menu interativo (ou painel, se não estiver num terminal)
zeus status       # mostra o painel uma vez
zeus 80           # modo tomada   (start=75 stop=80)
zeus 100          # modo viagem   (start=99 stop=100)
zeus set 55 60    # thresholds personalizados
zeus watch [N]    # painel ao vivo, atualiza a cada N segundos (padrão: 2)
zeus help         # ajuda
```

### Modos

| Modo | start | stop | Para quê |
|---|---|---|---|
| **tomada** | 75% | 80% | uso no dia a dia ligado na tomada, poupa a bateria |
| **viagem** | 99% | 100% | carga máxima antes de sair |
| **custom** | você escolhe | você escolhe | qualquer faixa com `0 < start < stop <= 100` |

### Atalhos do menu

| Tecla | Ação |
|---|---|
| `j` / `k` / setas | navegar |
| `Enter` / clique do mouse | selecionar |
| `1` a `4` | atalho direto para a opção |
| `q` / `Ctrl+C` | sair |

### O painel

```
 zeus :: BAT0 [SMP 5B11M90055]
 ------------------------------------------------
 [################################|.......]  80%
 ------------------------------------------------
 > status    IDLE  na tomada, carga travada pelo threshold
 > fonte     conectada  USB-C PD 65W max
 > modo      tomada  volta a carregar abaixo de 75%, para em 80%
 ------------------------------------------------
 > carga     80%  41.2 Wh guardados
 > fluxo     0.0 W  parada, notebook alimentado direto pela fonte
 > autonomia 5h12m fora da tomada  cheia: 6h30m  media de uso 7.9 W
 > estresse  baixo  faixa ideal de carga
 ------------------------------------------------
 > saude     98.3%  51.6 de 52.5 Wh de fabrica (-0.9 Wh)
 > ciclos    42  ciclos completos de carga
 > tensao    16.42 V  4.10 V/celula (4S)
 > temp      41 C  sistema (bateria nao tem sensor exposto)
 > quimica   Li-poly
```

<sub>*Exemplo ilustrativo: os valores variam conforme a máquina.*</sub>

| Campo | O que significa |
|---|---|
| **barra** | nível de carga; o `\|` ciano marca o limite (stop). No menu, um pulso `>` anda pela barra quando está carregando e `<` quando está descarregando |
| **status** | `CHARGING`, `DISCHARGING`, `FULL` ou `IDLE` (na tomada, mas parada pelo threshold) |
| **fonte** | se o carregador está conectado e, em USB-C, a potência máxima negociada |
| **modo** | tomada, viagem ou custom, deduzido a partir dos thresholds atuais |
| **carga** | percentual e energia armazenada em Wh |
| **fluxo** | potência entrando ou saindo da bateria, e tempo estimado até atingir o limite |
| **autonomia** | tempo estimado fora da tomada, com base no seu consumo médio real |
| **estresse** | estimativa qualitativa do desgaste no momento atual |
| **saude** | capacidade atual comparada com a capacidade de fábrica |
| **ciclos** | ciclos completos de carga contados pelo firmware da bateria |
| **tensao** | tensão total e por célula, com o número de células em série |
| **temp** | temperatura do sistema |

---

## Como funciona

### O sysfs e a classe `power_supply`

O Linux expõe o estado do hardware como arquivos de texto em `/sys`, um sistema de arquivos virtual mantido pelo próprio kernel (o *sysfs*). Nada ali existe em disco: ler um arquivo faz o kernel consultar o driver naquele instante, e gravar num arquivo faz o driver repassar o valor para o hardware.

Baterias, carregadores e portas USB-C aparecem em `/sys/class/power_supply/`:

```
/sys/class/power_supply/
├── AC/          adaptador de energia (online = 1 quando conectado)
├── BAT0/        a bateria
└── ucsi-source-psy-USBC000:001/   portas USB-C (driver UCSI)
```

O zeus só lê e grava arquivos desses diretórios. Ele não carrega módulos, não fala com ACPI diretamente e não mantém nenhum processo rodando.

### Como os thresholds funcionam

Em ThinkPads, o driver `thinkpad_acpi` conecta esses arquivos ao **Embedded Controller (EC)**, o microcontrolador da placa-mãe que de fato gerencia o carregamento. Quando você grava um valor, o driver chama um método ACPI que configura o EC, e a partir daí **o próprio hardware** respeita os limites, mesmo com o notebook desligado ou suspenso.

Os dois limites formam uma **histerese**:

- `charge_control_end_threshold` (**stop**): quando a carga chega nesse valor, o EC para de carregar e passa a alimentar o notebook direto pela fonte. A bateria fica parada (status `Not charging`).
- `charge_control_start_threshold` (**start**): a carga só volta a acontecer quando o nível cair abaixo desse valor.

Com o modo tomada (75/80), a bateria sobe até 80%, para, e só volta a carregar se cair para menos de 75%. Sem essa folga, o EC ficaria ligando e desligando a carga a cada variação de 1%, o que geraria microciclos inúteis.

O arquivo `charge_behaviour` controla o comportamento geral do carregamento. Ele lista as opções disponíveis e marca a ativa entre colchetes, por exemplo `[auto] inhibit-charge force-discharge`. O zeus mostra esse valor no painel quando ele não está em `auto`, e o modo viagem força `auto` para garantir que a bateria realmente carregue.

### Por que limitar a carga

Baterias de íon-lítio sofrem dois tipos de desgaste:

- **por ciclo**: cada carga e descarga consome um pouco da capacidade;
- **por calendário**: a bateria envelhece com o tempo, e esse envelhecimento é bem mais rápido quando ela fica parada em tensão alta, ou seja, perto de 100%, especialmente se estiver quente.

Um notebook que passa o dia na tomada a 100% está na pior situação possível para o envelhecimento por calendário. Travar em 80% reduz a tensão por célula (de cerca de 4,2 V para cerca de 4,0 V), o que diminui bastante esse desgaste. Em troca, você perde uns 20% de autonomia, por isso existe o modo viagem para quando você precisa dela.

O campo **estresse** do painel aplica essa lógica: marca `medio` quando a bateria está em 90% ou mais e parada na tomada, e `alto` quando a carga está em 15% ou menos.

### Gravação segura com `sudo`

Os arquivos de threshold só aceitam gravação pelo root. O zeus junta todas as gravações numa **única chamada** `sudo sh -c "..."`, então você digita a senha no máximo uma vez por alteração.

A **ordem** das gravações importa, porque o kernel recusa qualquer estado intermediário em que `start >= stop`. Exemplo: indo de 75/80 para 99/100, gravar o start primeiro produziria 99/80, que é inválido. Então o zeus compara o novo stop com o atual:

- se o stop vai **subir**, grava o stop primeiro e depois o start;
- se o stop vai **descer**, grava o start primeiro e depois o stop.

Ler o painel nunca precisa de privilégio.

### De onde vem cada informação

| Informação | Fonte no sysfs | Cálculo |
|---|---|---|
| carga | `BAT0/capacity`, `energy_now` | `energy_now` vem em µWh e é convertido para Wh |
| saúde | `energy_full`, `energy_full_design` | `energy_full / energy_full_design × 100` |
| fluxo | `power_now` | em µW; é o fluxo da bateria, não o consumo total do notebook |
| tempo até o limite | `energy_now`, `energy_full`, `power_now`, stop | `(energy_full × stop% − energy_now) / power_now` |
| ciclos | `cycle_count` | direto do firmware da bateria |
| células | `voltage_min_design` | tensão mínima de projeto ÷ 3,85 V (tensão nominal por célula) |
| fonte USB-C | `power_supply/*/type = USB`, `voltage_max`, `current_max`, `usb_type` | potência = V × A; PD se `usb_type` indicar Power Delivery |
| temperatura | `/sys/class/hwmon/*/temp1_input` | primeiro sensor entre `thinkpad`, `acpitz` e `coretemp` |

A temperatura é do sistema, e não da bateria, porque a `BAT0` desse ThinkPad não expõe um sensor de temperatura próprio.

### Estimativa de autonomia

O valor de `power_now` oscila muito: abrir uma aba no navegador pode fazer o consumo saltar de 5 W para 20 W por alguns segundos. Uma autonomia calculada com esse valor instantâneo pularia de 8 h para 2 h e voltaria a cada atualização.

Por isso o zeus mantém uma **média móvel exponencial** do consumo, atualizada sempre que o painel é desenhado com o notebook na bateria:

```
alpha = 1 − e^(−Δt / τ)
média = média + alpha × (consumo_atual − média)
```

- `τ` (tau) é de 600 s, então os últimos 10 minutos de uso dominam a média;
- `Δt` é o tempo desde a última leitura, limitado a 60 s, para que um intervalo longo (o notebook ficou dias na tomada) não apague o histórico de uma vez.

A média fica salva em `~/.cache/zeus/drain.edn`. Como ela só é atualizada na bateria, o painel continua mostrando a autonomia estimada mesmo na tomada ("quanto duraria se eu tirasse agora" e "quanto duraria com carga cheia").

### A interface no terminal

O menu é desenhado só com sequências de escape ANSI, sem bibliotecas de TUI:

- **tela alternativa** (`ESC[?1049h`): o menu abre numa tela separada, e ao sair o terminal volta exatamente como estava;
- **modo raw** via `stty -icanon -echo -isig`: cada tecla chega na hora, sem esperar Enter, e o `Ctrl+C` chega como um byte comum, para que a saída sempre passe pela rotina que restaura o terminal;
- **mouse SGR** (`ESC[?1000h ESC[?1006h`): o terminal envia cliques como `ESC[<0;x;yM`, e o zeus converte a linha `y` na opção do menu;
- **animação**: a tela é redesenhada a cada 150 ms (pulso na barra, raio no logo), mas o sysfs só é relido a cada 2 s ou logo após uma ação;
- **segurança**: um *shutdown hook* restaura o terminal mesmo se o processo for encerrado no meio de uma ação. Antes de chamar o `sudo`, o menu volta ao modo normal para que a senha possa ser digitada.

Quando a saída não é um terminal (por exemplo, `zeus | less` ou dentro de um script), o zeus mostra o painel uma vez e desativa as cores.

---

## Configuração

Variáveis de ambiente opcionais:

| Variável | Padrão | Descrição |
|---|---|---|
| `ZEUS_BAT` | `/sys/class/power_supply/BAT0` | caminho da bateria no sysfs |
| `ZEUS_AC` | `/sys/class/power_supply/AC` | caminho do adaptador no sysfs |
| `NO_COLOR` | (não definida) | se definida, desativa as cores |

Exemplo para uma bateria chamada `BAT1`:

```sh
ZEUS_BAT=/sys/class/power_supply/BAT1 zeus
```

---

## Perguntas frequentes

**Os thresholds sobrevivem a um reboot?**
Depende do firmware. Em muitos ThinkPads o EC guarda os valores, mas eles podem se perder se a bateria for desconectada ou o BIOS for atualizado. Se na sua máquina eles voltarem ao padrão, rode `zeus 80` de novo ou crie um serviço systemd que grave os valores na inicialização.

**Por que a bateria está em `IDLE` se o carregador está conectado?**
É o comportamento esperado: a carga atingiu o stop, e o notebook está sendo alimentado direto pela fonte, sem passar pela bateria.

**Por que o percentual às vezes passa um pouco do limite?**
O EC age sobre a leitura dele, que pode ficar 1% ou 2% diferente do `capacity` reportado pelo kernel. Isso é normal.

**Conflita com TLP ou outras ferramentas?**
Se o TLP estiver configurado para gerenciar os thresholds (`START_CHARGE_THRESH_BAT0` / `STOP_CHARGE_THRESH_BAT0`), ele vai sobrescrever o que o zeus gravou sempre que for executado. O mesmo vale para outras ferramentas de gerenciamento de bateria. Use apenas uma delas para cuidar dos thresholds.

---

## Feito com

- [Babashka](https://babashka.org): Clojure em script, com inicialização instantânea
- o `sysfs` do kernel Linux
- sequências de escape ANSI
