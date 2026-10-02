# OpenCode Android Shell Environment
export TERMUX__PREFIX=/data/data/a.opencode/files/usr
export TERMUX_APP_PACKAGE=a.opencode
export PATH="$HOME/bin:$TERMUX__PREFIX/bin:$PATH"

HISTFILE=~/.zsh_history
HISTSIZE=50000
SAVEHIST=50000
setopt APPEND_HISTORY SHARE_HISTORY HIST_IGNORE_DUPS HIST_IGNORE_SPACE

# Ensure open-debian is resolvable under all circumstances
if ! command -v open-debian >/dev/null 2>&1; then
    if [ -x "$HOME/bin/open-debian" ]; then
        open-debian() { "$HOME/bin/open-debian" "$@"; }
    elif [ -x "$TERMUX__PREFIX/bin/open-debian" ]; then
        open-debian() { "$TERMUX__PREFIX/bin/open-debian" "$@"; }
    fi
fi

# Clean colors and display
export LS_COLORS='di=1;34:ln=1;36:so=1;35:pi=1;33:ex=1;32:bd=1;33;44:cd=1;33;44:*.tar=1;31:*.tgz=1;31:*.gz=1;31:*.zip=1;31:*.deb=1;31:*.sh=1;32:*.py=1;32'
export GREP_COLORS='ms=01;31:mc=01;31:sl=:cx=:fn=01;34:ln=01;32:bn=01;32:se=01;36'
alias ls='ls --color=auto'
alias ll='ls -lah --color=auto'
alias la='ls -A --color=auto'
alias l='ls -CF --color=auto'
alias grep='grep --color=auto'

# OpenCode shortcuts
alias ..='cd ..'
alias ...='cd ../..'
alias c='clear'
alias h='history'
alias df='df -h'
alias du='du -sh'
alias free='free -h'
alias in='pkg install'
alias un='pkg uninstall'
alias up='pkg upgrade'
alias se='pkg search'
alias deb='open-debian debian'
alias ubu='open-debian ubuntu'
alias opencode='open-debian debian -w /root/opencode -- env PATH=/root/.opencode/bin:/usr/lib/jvm/java-21-openjdk-arm64/bin:/opt/gradle-8.7/bin:/opt/android-sdk/build-tools/35.0.1:/opt/android-sdk/cmdline-tools/latest/bin:/opt/node/bin:/usr/local/bin:/usr/bin:/bin HOME=/root JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk COLORTERM=truecolor TERM=xterm-256color opencode'
alias oc='opencode'
alias myip='curl -s ifconfig.me; echo'
alias weather='curl -s "wttr.in?format=3"'

mkcd() { mkdir -p "$1" && cd "$1"; }
extract() {
    local f="$1"
    [ -f "$f" ] || { echo "extract: file $f does not exist"; return 1; }
    case "$f" in
        *.tar.bz2|*.tbz2) tar xjf "$f" ;;
        *.tar.gz|*.tgz)   tar xzf "$f" ;;
        *.tar.xz|*.txz)   tar xJf "$f" ;;
        *.tar)            tar xf "$f" ;;
        *.zip)            unzip "$f" ;;
        *) echo "extract: unsupported archive format for $f" ; return 1 ;;
    esac
}

# Autocompletion and plugins
autoload -Uz compinit && compinit -C
zstyle ':completion:*' menu select
zstyle ':completion:*' list-colors "${(s.:.)LS_COLORS}"

[ -f ~/.zsh/zsh-autosuggestions/zsh-autosuggestions.zsh ] && source ~/.zsh/zsh-autosuggestions/zsh-autosuggestions.zsh
[ -f ~/.zsh/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ] && source ~/.zsh/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh

PROMPT='%F{cyan}%~%f %F{blue}❯%f '

# Auto-launch OpenCode on first interactive startup
if [[ -o interactive && -t 1 && -z "$OPENCODE_STARTED" ]]; then
    export OPENCODE_STARTED=1
    opencode
fi
