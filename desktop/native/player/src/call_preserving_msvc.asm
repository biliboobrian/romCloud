; Appel d'une fonction du cœur qui rétablit les registres que Windows impose de préserver (rbx, rbp,
; rdi, rsi, r12 à r15, xmm6 à xmm15) : version MASM (Visual Studio) du relais de core.cpp, qui
; contient la version des compilateurs GNU (llvm-mingw). Le recompilateur de PCSX ReARMed (Lightrec)
; écrase xmm6 à xmm15 sans les rétablir.
;
;   extern "C" void romcloud_call_preserving(void (*fn)());   ; fn dans rcx
;
; Pile : adresse de retour + 8 registres (alignée sur 16 ensuite), 32 octets réservés à l'appelé,
; 160 octets pour xmm6 à xmm15.

_TEXT SEGMENT

PUBLIC romcloud_call_preserving

romcloud_call_preserving PROC FRAME
    push rbx
    .pushreg rbx
    push rbp
    .pushreg rbp
    push rsi
    .pushreg rsi
    push rdi
    .pushreg rdi
    push r12
    .pushreg r12
    push r13
    .pushreg r13
    push r14
    .pushreg r14
    push r15
    .pushreg r15
    sub rsp, 200
    .allocstack 200
    movdqa xmmword ptr [rsp + 32], xmm6
    .savexmm128 xmm6, 32
    movdqa xmmword ptr [rsp + 48], xmm7
    .savexmm128 xmm7, 48
    movdqa xmmword ptr [rsp + 64], xmm8
    .savexmm128 xmm8, 64
    movdqa xmmword ptr [rsp + 80], xmm9
    .savexmm128 xmm9, 80
    movdqa xmmword ptr [rsp + 96], xmm10
    .savexmm128 xmm10, 96
    movdqa xmmword ptr [rsp + 112], xmm11
    .savexmm128 xmm11, 112
    movdqa xmmword ptr [rsp + 128], xmm12
    .savexmm128 xmm12, 128
    movdqa xmmword ptr [rsp + 144], xmm13
    .savexmm128 xmm13, 144
    movdqa xmmword ptr [rsp + 160], xmm14
    .savexmm128 xmm14, 160
    movdqa xmmword ptr [rsp + 176], xmm15
    .savexmm128 xmm15, 176
    .endprolog

    call rcx

    movdqa xmm6, xmmword ptr [rsp + 32]
    movdqa xmm7, xmmword ptr [rsp + 48]
    movdqa xmm8, xmmword ptr [rsp + 64]
    movdqa xmm9, xmmword ptr [rsp + 80]
    movdqa xmm10, xmmword ptr [rsp + 96]
    movdqa xmm11, xmmword ptr [rsp + 112]
    movdqa xmm12, xmmword ptr [rsp + 128]
    movdqa xmm13, xmmword ptr [rsp + 144]
    movdqa xmm14, xmmword ptr [rsp + 160]
    movdqa xmm15, xmmword ptr [rsp + 176]
    add rsp, 200
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbp
    pop rbx
    ret
romcloud_call_preserving ENDP

_TEXT ENDS

END
