<?php

    $i += 2;
    $i -= 2;
    $i *= 2;
    $i /= 2;
    $i .= '2';
    $i &= 2;
    $i |= 2;
    $i ^= 2;
    $i <<= 2;
    $i >>= 2;
    $i %= 2;

    $i = 2 + $i;
    $i = 2 - $i;
    $i = 2 * $i;
    $i = 2 / $i;
    $i = '2' . $i;
    $i = 2 & $i;
    $i = 2 | $i;
    $i = 2 ^ $i;
    $i = 2 << $i;
    $i = 2 >> $i;
    $i = 2 % $i;

    $i .= '=' . $i;
    $i .= '2' . '2';
    $i += 2 + 2;
    $i *= 2 * 2;

    $i = $i . '2' + '2';
    $i = $i / $number / $number;
    $i = $i - $number - $number;
    $i = $i & $number & $number;
    $i = $i | $number | $number;
    $i = $i ^ $number ^ $number;
    $i = $i << $number << $number;
    $i = $i >> $number >> $number;
    $i = $i % $number % $number;