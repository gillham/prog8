
%import textio
%import floats
%import test_stack
%zeropage dontuse
%option no_sysinit

main {
    uword success = 0
    str datatype = "word"
    uword @shared comparison

    sub start() {
        txt.print("\nless-than split words array tests for: ")
        txt.print(datatype)
        txt.nl()
        test_stack.test()
        txt.print("\n<array[]: ")
        test_cmp_array()
        test_stack.test()
    }
    
    sub verify_success(uword expected) {
        if success==expected {
            txt.print("ok")
        } else {
            txt.print(" **failed** ")
            txt.print_uw(success)
            txt.print(" success, expected ")
            txt.print_uw(expected)
        }
    }
    
    sub fail_word(uword idx) {
        txt.print(" **fail#")
        txt.print_uw(idx)
        txt.print(" **")
    }

    sub fail_uword(uword idx) {
        txt.print(" **fail#")
        txt.print_uw(idx)
        txt.print(" **")
    }
    

    sub test_cmp_array() {
    word @shared x
        word[] @split values = [0, 0]
        word[] @split sources = [0, 0]
        success = 0
    x=-21829
    sources[1]=-21829
    values[1]=-21829
    ; direct jump
        if x<values[1]
            goto lbl1a
        goto skip1a
lbl1a:   fail_word(1)
skip1a:
        ; indirect jump
        cx16.r3 = &lbl1b
        if x<values[1]
            goto cx16.r3
        goto skip1b
lbl1b:   fail_word(2)
skip1b:
        ; no else
        if x<values[1]
            fail_word(3)

        ; with else
        if x<values[1]
            fail_word(4)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl1c
        goto skip1c
lbl1c:   fail_word(5)
skip1c:
        ; indirect jump
        cx16.r3 = &lbl1d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip1d
lbl1d:   fail_word(6)
skip1d:
        ; no else
        if sources[1]<values[1]
            fail_word(7)

        ; with else
        if sources[1]<values[1]
            fail_word(8)
        else
            success++

    values[1]=-1
    ; direct jump
        if x<values[1]
            goto lbl2a
        goto skip2a
lbl2a:   success++
skip2a:
        ; indirect jump
        cx16.r3 = &lbl2b
        if x<values[1]
            goto cx16.r3
        goto skip2b
lbl2b:   success++
skip2b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl2c
        goto skip2c
lbl2c:   success++
skip2c:
        ; indirect jump
        cx16.r3 = &lbl2d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip2d
lbl2d:   success++
skip2d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=0
    ; direct jump
        if x<values[1]
            goto lbl3a
        goto skip3a
lbl3a:   success++
skip3a:
        ; indirect jump
        cx16.r3 = &lbl3b
        if x<values[1]
            goto cx16.r3
        goto skip3b
lbl3b:   success++
skip3b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl3c
        goto skip3c
lbl3c:   success++
skip3c:
        ; indirect jump
        cx16.r3 = &lbl3d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip3d
lbl3d:   success++
skip3d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=1
    ; direct jump
        if x<values[1]
            goto lbl4a
        goto skip4a
lbl4a:   success++
skip4a:
        ; indirect jump
        cx16.r3 = &lbl4b
        if x<values[1]
            goto cx16.r3
        goto skip4b
lbl4b:   success++
skip4b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl4c
        goto skip4c
lbl4c:   success++
skip4c:
        ; indirect jump
        cx16.r3 = &lbl4d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip4d
lbl4d:   success++
skip4d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=170
    ; direct jump
        if x<values[1]
            goto lbl5a
        goto skip5a
lbl5a:   success++
skip5a:
        ; indirect jump
        cx16.r3 = &lbl5b
        if x<values[1]
            goto cx16.r3
        goto skip5b
lbl5b:   success++
skip5b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl5c
        goto skip5c
lbl5c:   success++
skip5c:
        ; indirect jump
        cx16.r3 = &lbl5d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip5d
lbl5d:   success++
skip5d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=30464
    ; direct jump
        if x<values[1]
            goto lbl6a
        goto skip6a
lbl6a:   success++
skip6a:
        ; indirect jump
        cx16.r3 = &lbl6b
        if x<values[1]
            goto cx16.r3
        goto skip6b
lbl6b:   success++
skip6b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl6c
        goto skip6c
lbl6c:   success++
skip6c:
        ; indirect jump
        cx16.r3 = &lbl6d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip6d
lbl6d:   success++
skip6d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=32767
    ; direct jump
        if x<values[1]
            goto lbl7a
        goto skip7a
lbl7a:   success++
skip7a:
        ; indirect jump
        cx16.r3 = &lbl7b
        if x<values[1]
            goto cx16.r3
        goto skip7b
lbl7b:   success++
skip7b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl7c
        goto skip7c
lbl7c:   success++
skip7c:
        ; indirect jump
        cx16.r3 = &lbl7d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip7d
lbl7d:   success++
skip7d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    x=-1
    sources[1]=-1
    values[1]=-21829
    ; direct jump
        if x<values[1]
            goto lbl8a
        goto skip8a
lbl8a:   fail_word(9)
skip8a:
        ; indirect jump
        cx16.r3 = &lbl8b
        if x<values[1]
            goto cx16.r3
        goto skip8b
lbl8b:   fail_word(10)
skip8b:
        ; no else
        if x<values[1]
            fail_word(11)

        ; with else
        if x<values[1]
            fail_word(12)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl8c
        goto skip8c
lbl8c:   fail_word(13)
skip8c:
        ; indirect jump
        cx16.r3 = &lbl8d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip8d
lbl8d:   fail_word(14)
skip8d:
        ; no else
        if sources[1]<values[1]
            fail_word(15)

        ; with else
        if sources[1]<values[1]
            fail_word(16)
        else
            success++

    values[1]=-1
    ; direct jump
        if x<values[1]
            goto lbl9a
        goto skip9a
lbl9a:   fail_word(17)
skip9a:
        ; indirect jump
        cx16.r3 = &lbl9b
        if x<values[1]
            goto cx16.r3
        goto skip9b
lbl9b:   fail_word(18)
skip9b:
        ; no else
        if x<values[1]
            fail_word(19)

        ; with else
        if x<values[1]
            fail_word(20)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl9c
        goto skip9c
lbl9c:   fail_word(21)
skip9c:
        ; indirect jump
        cx16.r3 = &lbl9d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip9d
lbl9d:   fail_word(22)
skip9d:
        ; no else
        if sources[1]<values[1]
            fail_word(23)

        ; with else
        if sources[1]<values[1]
            fail_word(24)
        else
            success++

    values[1]=0
    ; direct jump
        if x<values[1]
            goto lbl10a
        goto skip10a
lbl10a:   success++
skip10a:
        ; indirect jump
        cx16.r3 = &lbl10b
        if x<values[1]
            goto cx16.r3
        goto skip10b
lbl10b:   success++
skip10b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl10c
        goto skip10c
lbl10c:   success++
skip10c:
        ; indirect jump
        cx16.r3 = &lbl10d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip10d
lbl10d:   success++
skip10d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=1
    ; direct jump
        if x<values[1]
            goto lbl11a
        goto skip11a
lbl11a:   success++
skip11a:
        ; indirect jump
        cx16.r3 = &lbl11b
        if x<values[1]
            goto cx16.r3
        goto skip11b
lbl11b:   success++
skip11b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl11c
        goto skip11c
lbl11c:   success++
skip11c:
        ; indirect jump
        cx16.r3 = &lbl11d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip11d
lbl11d:   success++
skip11d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=170
    ; direct jump
        if x<values[1]
            goto lbl12a
        goto skip12a
lbl12a:   success++
skip12a:
        ; indirect jump
        cx16.r3 = &lbl12b
        if x<values[1]
            goto cx16.r3
        goto skip12b
lbl12b:   success++
skip12b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl12c
        goto skip12c
lbl12c:   success++
skip12c:
        ; indirect jump
        cx16.r3 = &lbl12d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip12d
lbl12d:   success++
skip12d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=30464
    ; direct jump
        if x<values[1]
            goto lbl13a
        goto skip13a
lbl13a:   success++
skip13a:
        ; indirect jump
        cx16.r3 = &lbl13b
        if x<values[1]
            goto cx16.r3
        goto skip13b
lbl13b:   success++
skip13b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl13c
        goto skip13c
lbl13c:   success++
skip13c:
        ; indirect jump
        cx16.r3 = &lbl13d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip13d
lbl13d:   success++
skip13d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=32767
    ; direct jump
        if x<values[1]
            goto lbl14a
        goto skip14a
lbl14a:   success++
skip14a:
        ; indirect jump
        cx16.r3 = &lbl14b
        if x<values[1]
            goto cx16.r3
        goto skip14b
lbl14b:   success++
skip14b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl14c
        goto skip14c
lbl14c:   success++
skip14c:
        ; indirect jump
        cx16.r3 = &lbl14d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip14d
lbl14d:   success++
skip14d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    x=0
    sources[1]=0
    values[1]=-21829
    ; direct jump
        if x<values[1]
            goto lbl15a
        goto skip15a
lbl15a:   fail_word(25)
skip15a:
        ; indirect jump
        cx16.r3 = &lbl15b
        if x<values[1]
            goto cx16.r3
        goto skip15b
lbl15b:   fail_word(26)
skip15b:
        ; no else
        if x<values[1]
            fail_word(27)

        ; with else
        if x<values[1]
            fail_word(28)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl15c
        goto skip15c
lbl15c:   fail_word(29)
skip15c:
        ; indirect jump
        cx16.r3 = &lbl15d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip15d
lbl15d:   fail_word(30)
skip15d:
        ; no else
        if sources[1]<values[1]
            fail_word(31)

        ; with else
        if sources[1]<values[1]
            fail_word(32)
        else
            success++

    values[1]=-1
    ; direct jump
        if x<values[1]
            goto lbl16a
        goto skip16a
lbl16a:   fail_word(33)
skip16a:
        ; indirect jump
        cx16.r3 = &lbl16b
        if x<values[1]
            goto cx16.r3
        goto skip16b
lbl16b:   fail_word(34)
skip16b:
        ; no else
        if x<values[1]
            fail_word(35)

        ; with else
        if x<values[1]
            fail_word(36)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl16c
        goto skip16c
lbl16c:   fail_word(37)
skip16c:
        ; indirect jump
        cx16.r3 = &lbl16d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip16d
lbl16d:   fail_word(38)
skip16d:
        ; no else
        if sources[1]<values[1]
            fail_word(39)

        ; with else
        if sources[1]<values[1]
            fail_word(40)
        else
            success++

    values[1]=0
    ; direct jump
        if x<values[1]
            goto lbl17a
        goto skip17a
lbl17a:   fail_word(41)
skip17a:
        ; indirect jump
        cx16.r3 = &lbl17b
        if x<values[1]
            goto cx16.r3
        goto skip17b
lbl17b:   fail_word(42)
skip17b:
        ; no else
        if x<values[1]
            fail_word(43)

        ; with else
        if x<values[1]
            fail_word(44)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl17c
        goto skip17c
lbl17c:   fail_word(45)
skip17c:
        ; indirect jump
        cx16.r3 = &lbl17d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip17d
lbl17d:   fail_word(46)
skip17d:
        ; no else
        if sources[1]<values[1]
            fail_word(47)

        ; with else
        if sources[1]<values[1]
            fail_word(48)
        else
            success++

    values[1]=1
    ; direct jump
        if x<values[1]
            goto lbl18a
        goto skip18a
lbl18a:   success++
skip18a:
        ; indirect jump
        cx16.r3 = &lbl18b
        if x<values[1]
            goto cx16.r3
        goto skip18b
lbl18b:   success++
skip18b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl18c
        goto skip18c
lbl18c:   success++
skip18c:
        ; indirect jump
        cx16.r3 = &lbl18d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip18d
lbl18d:   success++
skip18d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=170
    ; direct jump
        if x<values[1]
            goto lbl19a
        goto skip19a
lbl19a:   success++
skip19a:
        ; indirect jump
        cx16.r3 = &lbl19b
        if x<values[1]
            goto cx16.r3
        goto skip19b
lbl19b:   success++
skip19b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl19c
        goto skip19c
lbl19c:   success++
skip19c:
        ; indirect jump
        cx16.r3 = &lbl19d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip19d
lbl19d:   success++
skip19d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=30464
    ; direct jump
        if x<values[1]
            goto lbl20a
        goto skip20a
lbl20a:   success++
skip20a:
        ; indirect jump
        cx16.r3 = &lbl20b
        if x<values[1]
            goto cx16.r3
        goto skip20b
lbl20b:   success++
skip20b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl20c
        goto skip20c
lbl20c:   success++
skip20c:
        ; indirect jump
        cx16.r3 = &lbl20d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip20d
lbl20d:   success++
skip20d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=32767
    ; direct jump
        if x<values[1]
            goto lbl21a
        goto skip21a
lbl21a:   success++
skip21a:
        ; indirect jump
        cx16.r3 = &lbl21b
        if x<values[1]
            goto cx16.r3
        goto skip21b
lbl21b:   success++
skip21b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl21c
        goto skip21c
lbl21c:   success++
skip21c:
        ; indirect jump
        cx16.r3 = &lbl21d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip21d
lbl21d:   success++
skip21d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    x=1
    sources[1]=1
    values[1]=-21829
    ; direct jump
        if x<values[1]
            goto lbl22a
        goto skip22a
lbl22a:   fail_word(49)
skip22a:
        ; indirect jump
        cx16.r3 = &lbl22b
        if x<values[1]
            goto cx16.r3
        goto skip22b
lbl22b:   fail_word(50)
skip22b:
        ; no else
        if x<values[1]
            fail_word(51)

        ; with else
        if x<values[1]
            fail_word(52)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl22c
        goto skip22c
lbl22c:   fail_word(53)
skip22c:
        ; indirect jump
        cx16.r3 = &lbl22d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip22d
lbl22d:   fail_word(54)
skip22d:
        ; no else
        if sources[1]<values[1]
            fail_word(55)

        ; with else
        if sources[1]<values[1]
            fail_word(56)
        else
            success++

    values[1]=-1
    ; direct jump
        if x<values[1]
            goto lbl23a
        goto skip23a
lbl23a:   fail_word(57)
skip23a:
        ; indirect jump
        cx16.r3 = &lbl23b
        if x<values[1]
            goto cx16.r3
        goto skip23b
lbl23b:   fail_word(58)
skip23b:
        ; no else
        if x<values[1]
            fail_word(59)

        ; with else
        if x<values[1]
            fail_word(60)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl23c
        goto skip23c
lbl23c:   fail_word(61)
skip23c:
        ; indirect jump
        cx16.r3 = &lbl23d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip23d
lbl23d:   fail_word(62)
skip23d:
        ; no else
        if sources[1]<values[1]
            fail_word(63)

        ; with else
        if sources[1]<values[1]
            fail_word(64)
        else
            success++

    values[1]=0
    ; direct jump
        if x<values[1]
            goto lbl24a
        goto skip24a
lbl24a:   fail_word(65)
skip24a:
        ; indirect jump
        cx16.r3 = &lbl24b
        if x<values[1]
            goto cx16.r3
        goto skip24b
lbl24b:   fail_word(66)
skip24b:
        ; no else
        if x<values[1]
            fail_word(67)

        ; with else
        if x<values[1]
            fail_word(68)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl24c
        goto skip24c
lbl24c:   fail_word(69)
skip24c:
        ; indirect jump
        cx16.r3 = &lbl24d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip24d
lbl24d:   fail_word(70)
skip24d:
        ; no else
        if sources[1]<values[1]
            fail_word(71)

        ; with else
        if sources[1]<values[1]
            fail_word(72)
        else
            success++

    values[1]=1
    ; direct jump
        if x<values[1]
            goto lbl25a
        goto skip25a
lbl25a:   fail_word(73)
skip25a:
        ; indirect jump
        cx16.r3 = &lbl25b
        if x<values[1]
            goto cx16.r3
        goto skip25b
lbl25b:   fail_word(74)
skip25b:
        ; no else
        if x<values[1]
            fail_word(75)

        ; with else
        if x<values[1]
            fail_word(76)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl25c
        goto skip25c
lbl25c:   fail_word(77)
skip25c:
        ; indirect jump
        cx16.r3 = &lbl25d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip25d
lbl25d:   fail_word(78)
skip25d:
        ; no else
        if sources[1]<values[1]
            fail_word(79)

        ; with else
        if sources[1]<values[1]
            fail_word(80)
        else
            success++

    values[1]=170
    ; direct jump
        if x<values[1]
            goto lbl26a
        goto skip26a
lbl26a:   success++
skip26a:
        ; indirect jump
        cx16.r3 = &lbl26b
        if x<values[1]
            goto cx16.r3
        goto skip26b
lbl26b:   success++
skip26b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl26c
        goto skip26c
lbl26c:   success++
skip26c:
        ; indirect jump
        cx16.r3 = &lbl26d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip26d
lbl26d:   success++
skip26d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=30464
    ; direct jump
        if x<values[1]
            goto lbl27a
        goto skip27a
lbl27a:   success++
skip27a:
        ; indirect jump
        cx16.r3 = &lbl27b
        if x<values[1]
            goto cx16.r3
        goto skip27b
lbl27b:   success++
skip27b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl27c
        goto skip27c
lbl27c:   success++
skip27c:
        ; indirect jump
        cx16.r3 = &lbl27d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip27d
lbl27d:   success++
skip27d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=32767
    ; direct jump
        if x<values[1]
            goto lbl28a
        goto skip28a
lbl28a:   success++
skip28a:
        ; indirect jump
        cx16.r3 = &lbl28b
        if x<values[1]
            goto cx16.r3
        goto skip28b
lbl28b:   success++
skip28b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl28c
        goto skip28c
lbl28c:   success++
skip28c:
        ; indirect jump
        cx16.r3 = &lbl28d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip28d
lbl28d:   success++
skip28d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    x=170
    sources[1]=170
    values[1]=-21829
    ; direct jump
        if x<values[1]
            goto lbl29a
        goto skip29a
lbl29a:   fail_word(81)
skip29a:
        ; indirect jump
        cx16.r3 = &lbl29b
        if x<values[1]
            goto cx16.r3
        goto skip29b
lbl29b:   fail_word(82)
skip29b:
        ; no else
        if x<values[1]
            fail_word(83)

        ; with else
        if x<values[1]
            fail_word(84)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl29c
        goto skip29c
lbl29c:   fail_word(85)
skip29c:
        ; indirect jump
        cx16.r3 = &lbl29d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip29d
lbl29d:   fail_word(86)
skip29d:
        ; no else
        if sources[1]<values[1]
            fail_word(87)

        ; with else
        if sources[1]<values[1]
            fail_word(88)
        else
            success++

    values[1]=-1
    ; direct jump
        if x<values[1]
            goto lbl30a
        goto skip30a
lbl30a:   fail_word(89)
skip30a:
        ; indirect jump
        cx16.r3 = &lbl30b
        if x<values[1]
            goto cx16.r3
        goto skip30b
lbl30b:   fail_word(90)
skip30b:
        ; no else
        if x<values[1]
            fail_word(91)

        ; with else
        if x<values[1]
            fail_word(92)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl30c
        goto skip30c
lbl30c:   fail_word(93)
skip30c:
        ; indirect jump
        cx16.r3 = &lbl30d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip30d
lbl30d:   fail_word(94)
skip30d:
        ; no else
        if sources[1]<values[1]
            fail_word(95)

        ; with else
        if sources[1]<values[1]
            fail_word(96)
        else
            success++

    values[1]=0
    ; direct jump
        if x<values[1]
            goto lbl31a
        goto skip31a
lbl31a:   fail_word(97)
skip31a:
        ; indirect jump
        cx16.r3 = &lbl31b
        if x<values[1]
            goto cx16.r3
        goto skip31b
lbl31b:   fail_word(98)
skip31b:
        ; no else
        if x<values[1]
            fail_word(99)

        ; with else
        if x<values[1]
            fail_word(100)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl31c
        goto skip31c
lbl31c:   fail_word(101)
skip31c:
        ; indirect jump
        cx16.r3 = &lbl31d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip31d
lbl31d:   fail_word(102)
skip31d:
        ; no else
        if sources[1]<values[1]
            fail_word(103)

        ; with else
        if sources[1]<values[1]
            fail_word(104)
        else
            success++

    values[1]=1
    ; direct jump
        if x<values[1]
            goto lbl32a
        goto skip32a
lbl32a:   fail_word(105)
skip32a:
        ; indirect jump
        cx16.r3 = &lbl32b
        if x<values[1]
            goto cx16.r3
        goto skip32b
lbl32b:   fail_word(106)
skip32b:
        ; no else
        if x<values[1]
            fail_word(107)

        ; with else
        if x<values[1]
            fail_word(108)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl32c
        goto skip32c
lbl32c:   fail_word(109)
skip32c:
        ; indirect jump
        cx16.r3 = &lbl32d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip32d
lbl32d:   fail_word(110)
skip32d:
        ; no else
        if sources[1]<values[1]
            fail_word(111)

        ; with else
        if sources[1]<values[1]
            fail_word(112)
        else
            success++

    values[1]=170
    ; direct jump
        if x<values[1]
            goto lbl33a
        goto skip33a
lbl33a:   fail_word(113)
skip33a:
        ; indirect jump
        cx16.r3 = &lbl33b
        if x<values[1]
            goto cx16.r3
        goto skip33b
lbl33b:   fail_word(114)
skip33b:
        ; no else
        if x<values[1]
            fail_word(115)

        ; with else
        if x<values[1]
            fail_word(116)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl33c
        goto skip33c
lbl33c:   fail_word(117)
skip33c:
        ; indirect jump
        cx16.r3 = &lbl33d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip33d
lbl33d:   fail_word(118)
skip33d:
        ; no else
        if sources[1]<values[1]
            fail_word(119)

        ; with else
        if sources[1]<values[1]
            fail_word(120)
        else
            success++

    values[1]=30464
    ; direct jump
        if x<values[1]
            goto lbl34a
        goto skip34a
lbl34a:   success++
skip34a:
        ; indirect jump
        cx16.r3 = &lbl34b
        if x<values[1]
            goto cx16.r3
        goto skip34b
lbl34b:   success++
skip34b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl34c
        goto skip34c
lbl34c:   success++
skip34c:
        ; indirect jump
        cx16.r3 = &lbl34d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip34d
lbl34d:   success++
skip34d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    values[1]=32767
    ; direct jump
        if x<values[1]
            goto lbl35a
        goto skip35a
lbl35a:   success++
skip35a:
        ; indirect jump
        cx16.r3 = &lbl35b
        if x<values[1]
            goto cx16.r3
        goto skip35b
lbl35b:   success++
skip35b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl35c
        goto skip35c
lbl35c:   success++
skip35c:
        ; indirect jump
        cx16.r3 = &lbl35d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip35d
lbl35d:   success++
skip35d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    x=30464
    sources[1]=30464
    values[1]=-21829
    ; direct jump
        if x<values[1]
            goto lbl36a
        goto skip36a
lbl36a:   fail_word(121)
skip36a:
        ; indirect jump
        cx16.r3 = &lbl36b
        if x<values[1]
            goto cx16.r3
        goto skip36b
lbl36b:   fail_word(122)
skip36b:
        ; no else
        if x<values[1]
            fail_word(123)

        ; with else
        if x<values[1]
            fail_word(124)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl36c
        goto skip36c
lbl36c:   fail_word(125)
skip36c:
        ; indirect jump
        cx16.r3 = &lbl36d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip36d
lbl36d:   fail_word(126)
skip36d:
        ; no else
        if sources[1]<values[1]
            fail_word(127)

        ; with else
        if sources[1]<values[1]
            fail_word(128)
        else
            success++

    values[1]=-1
    ; direct jump
        if x<values[1]
            goto lbl37a
        goto skip37a
lbl37a:   fail_word(129)
skip37a:
        ; indirect jump
        cx16.r3 = &lbl37b
        if x<values[1]
            goto cx16.r3
        goto skip37b
lbl37b:   fail_word(130)
skip37b:
        ; no else
        if x<values[1]
            fail_word(131)

        ; with else
        if x<values[1]
            fail_word(132)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl37c
        goto skip37c
lbl37c:   fail_word(133)
skip37c:
        ; indirect jump
        cx16.r3 = &lbl37d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip37d
lbl37d:   fail_word(134)
skip37d:
        ; no else
        if sources[1]<values[1]
            fail_word(135)

        ; with else
        if sources[1]<values[1]
            fail_word(136)
        else
            success++

    values[1]=0
    ; direct jump
        if x<values[1]
            goto lbl38a
        goto skip38a
lbl38a:   fail_word(137)
skip38a:
        ; indirect jump
        cx16.r3 = &lbl38b
        if x<values[1]
            goto cx16.r3
        goto skip38b
lbl38b:   fail_word(138)
skip38b:
        ; no else
        if x<values[1]
            fail_word(139)

        ; with else
        if x<values[1]
            fail_word(140)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl38c
        goto skip38c
lbl38c:   fail_word(141)
skip38c:
        ; indirect jump
        cx16.r3 = &lbl38d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip38d
lbl38d:   fail_word(142)
skip38d:
        ; no else
        if sources[1]<values[1]
            fail_word(143)

        ; with else
        if sources[1]<values[1]
            fail_word(144)
        else
            success++

    values[1]=1
    ; direct jump
        if x<values[1]
            goto lbl39a
        goto skip39a
lbl39a:   fail_word(145)
skip39a:
        ; indirect jump
        cx16.r3 = &lbl39b
        if x<values[1]
            goto cx16.r3
        goto skip39b
lbl39b:   fail_word(146)
skip39b:
        ; no else
        if x<values[1]
            fail_word(147)

        ; with else
        if x<values[1]
            fail_word(148)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl39c
        goto skip39c
lbl39c:   fail_word(149)
skip39c:
        ; indirect jump
        cx16.r3 = &lbl39d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip39d
lbl39d:   fail_word(150)
skip39d:
        ; no else
        if sources[1]<values[1]
            fail_word(151)

        ; with else
        if sources[1]<values[1]
            fail_word(152)
        else
            success++

    values[1]=170
    ; direct jump
        if x<values[1]
            goto lbl40a
        goto skip40a
lbl40a:   fail_word(153)
skip40a:
        ; indirect jump
        cx16.r3 = &lbl40b
        if x<values[1]
            goto cx16.r3
        goto skip40b
lbl40b:   fail_word(154)
skip40b:
        ; no else
        if x<values[1]
            fail_word(155)

        ; with else
        if x<values[1]
            fail_word(156)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl40c
        goto skip40c
lbl40c:   fail_word(157)
skip40c:
        ; indirect jump
        cx16.r3 = &lbl40d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip40d
lbl40d:   fail_word(158)
skip40d:
        ; no else
        if sources[1]<values[1]
            fail_word(159)

        ; with else
        if sources[1]<values[1]
            fail_word(160)
        else
            success++

    values[1]=30464
    ; direct jump
        if x<values[1]
            goto lbl41a
        goto skip41a
lbl41a:   fail_word(161)
skip41a:
        ; indirect jump
        cx16.r3 = &lbl41b
        if x<values[1]
            goto cx16.r3
        goto skip41b
lbl41b:   fail_word(162)
skip41b:
        ; no else
        if x<values[1]
            fail_word(163)

        ; with else
        if x<values[1]
            fail_word(164)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl41c
        goto skip41c
lbl41c:   fail_word(165)
skip41c:
        ; indirect jump
        cx16.r3 = &lbl41d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip41d
lbl41d:   fail_word(166)
skip41d:
        ; no else
        if sources[1]<values[1]
            fail_word(167)

        ; with else
        if sources[1]<values[1]
            fail_word(168)
        else
            success++

    values[1]=32767
    ; direct jump
        if x<values[1]
            goto lbl42a
        goto skip42a
lbl42a:   success++
skip42a:
        ; indirect jump
        cx16.r3 = &lbl42b
        if x<values[1]
            goto cx16.r3
        goto skip42b
lbl42b:   success++
skip42b:
        ; no else
        if x<values[1]
            success++

        ; with else
        if x<values[1]
            success++
        else
            cx16.r0L++

    ; direct jump
        if sources[1]<values[1]
            goto lbl42c
        goto skip42c
lbl42c:   success++
skip42c:
        ; indirect jump
        cx16.r3 = &lbl42d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip42d
lbl42d:   success++
skip42d:
        ; no else
        if sources[1]<values[1]
            success++

        ; with else
        if sources[1]<values[1]
            success++
        else
            cx16.r0L++

    x=32767
    sources[1]=32767
    values[1]=-21829
    ; direct jump
        if x<values[1]
            goto lbl43a
        goto skip43a
lbl43a:   fail_word(169)
skip43a:
        ; indirect jump
        cx16.r3 = &lbl43b
        if x<values[1]
            goto cx16.r3
        goto skip43b
lbl43b:   fail_word(170)
skip43b:
        ; no else
        if x<values[1]
            fail_word(171)

        ; with else
        if x<values[1]
            fail_word(172)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl43c
        goto skip43c
lbl43c:   fail_word(173)
skip43c:
        ; indirect jump
        cx16.r3 = &lbl43d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip43d
lbl43d:   fail_word(174)
skip43d:
        ; no else
        if sources[1]<values[1]
            fail_word(175)

        ; with else
        if sources[1]<values[1]
            fail_word(176)
        else
            success++

    values[1]=-1
    ; direct jump
        if x<values[1]
            goto lbl44a
        goto skip44a
lbl44a:   fail_word(177)
skip44a:
        ; indirect jump
        cx16.r3 = &lbl44b
        if x<values[1]
            goto cx16.r3
        goto skip44b
lbl44b:   fail_word(178)
skip44b:
        ; no else
        if x<values[1]
            fail_word(179)

        ; with else
        if x<values[1]
            fail_word(180)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl44c
        goto skip44c
lbl44c:   fail_word(181)
skip44c:
        ; indirect jump
        cx16.r3 = &lbl44d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip44d
lbl44d:   fail_word(182)
skip44d:
        ; no else
        if sources[1]<values[1]
            fail_word(183)

        ; with else
        if sources[1]<values[1]
            fail_word(184)
        else
            success++

    values[1]=0
    ; direct jump
        if x<values[1]
            goto lbl45a
        goto skip45a
lbl45a:   fail_word(185)
skip45a:
        ; indirect jump
        cx16.r3 = &lbl45b
        if x<values[1]
            goto cx16.r3
        goto skip45b
lbl45b:   fail_word(186)
skip45b:
        ; no else
        if x<values[1]
            fail_word(187)

        ; with else
        if x<values[1]
            fail_word(188)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl45c
        goto skip45c
lbl45c:   fail_word(189)
skip45c:
        ; indirect jump
        cx16.r3 = &lbl45d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip45d
lbl45d:   fail_word(190)
skip45d:
        ; no else
        if sources[1]<values[1]
            fail_word(191)

        ; with else
        if sources[1]<values[1]
            fail_word(192)
        else
            success++

    values[1]=1
    ; direct jump
        if x<values[1]
            goto lbl46a
        goto skip46a
lbl46a:   fail_word(193)
skip46a:
        ; indirect jump
        cx16.r3 = &lbl46b
        if x<values[1]
            goto cx16.r3
        goto skip46b
lbl46b:   fail_word(194)
skip46b:
        ; no else
        if x<values[1]
            fail_word(195)

        ; with else
        if x<values[1]
            fail_word(196)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl46c
        goto skip46c
lbl46c:   fail_word(197)
skip46c:
        ; indirect jump
        cx16.r3 = &lbl46d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip46d
lbl46d:   fail_word(198)
skip46d:
        ; no else
        if sources[1]<values[1]
            fail_word(199)

        ; with else
        if sources[1]<values[1]
            fail_word(200)
        else
            success++

    values[1]=170
    ; direct jump
        if x<values[1]
            goto lbl47a
        goto skip47a
lbl47a:   fail_word(201)
skip47a:
        ; indirect jump
        cx16.r3 = &lbl47b
        if x<values[1]
            goto cx16.r3
        goto skip47b
lbl47b:   fail_word(202)
skip47b:
        ; no else
        if x<values[1]
            fail_word(203)

        ; with else
        if x<values[1]
            fail_word(204)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl47c
        goto skip47c
lbl47c:   fail_word(205)
skip47c:
        ; indirect jump
        cx16.r3 = &lbl47d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip47d
lbl47d:   fail_word(206)
skip47d:
        ; no else
        if sources[1]<values[1]
            fail_word(207)

        ; with else
        if sources[1]<values[1]
            fail_word(208)
        else
            success++

    values[1]=30464
    ; direct jump
        if x<values[1]
            goto lbl48a
        goto skip48a
lbl48a:   fail_word(209)
skip48a:
        ; indirect jump
        cx16.r3 = &lbl48b
        if x<values[1]
            goto cx16.r3
        goto skip48b
lbl48b:   fail_word(210)
skip48b:
        ; no else
        if x<values[1]
            fail_word(211)

        ; with else
        if x<values[1]
            fail_word(212)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl48c
        goto skip48c
lbl48c:   fail_word(213)
skip48c:
        ; indirect jump
        cx16.r3 = &lbl48d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip48d
lbl48d:   fail_word(214)
skip48d:
        ; no else
        if sources[1]<values[1]
            fail_word(215)

        ; with else
        if sources[1]<values[1]
            fail_word(216)
        else
            success++

    values[1]=32767
    ; direct jump
        if x<values[1]
            goto lbl49a
        goto skip49a
lbl49a:   fail_word(217)
skip49a:
        ; indirect jump
        cx16.r3 = &lbl49b
        if x<values[1]
            goto cx16.r3
        goto skip49b
lbl49b:   fail_word(218)
skip49b:
        ; no else
        if x<values[1]
            fail_word(219)

        ; with else
        if x<values[1]
            fail_word(220)
        else
            success++

    ; direct jump
        if sources[1]<values[1]
            goto lbl49c
        goto skip49c
lbl49c:   fail_word(221)
skip49c:
        ; indirect jump
        cx16.r3 = &lbl49d
        if sources[1]<values[1]
            goto cx16.r3
        goto skip49d
lbl49d:   fail_word(222)
skip49d:
        ; no else
        if sources[1]<values[1]
            fail_word(223)

        ; with else
        if sources[1]<values[1]
            fail_word(224)
        else
            success++

    verify_success(224)
}

}
