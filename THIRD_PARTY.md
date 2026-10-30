# サードパーティソフトウェア

## JTS Topology Suite (jts-core)

- バージョン: 1.20.0
- 用途: パスのブーリアン演算（union / difference / intersection / symmetric difference）、オフセット（BufferOp）、単純化（Douglas-Peucker）
- ライセンス: Eclipse Public License 2.0 / Eclipse Distribution License 1.0 (BSD-3-Clause) のデュアルライセンス
- ソース: https://github.com/locationtech/jts
- Copyright (c) 2019 Vivid Solutions, Inc. and others

EPL-2.0 / EDL-1.0 のライセンス条文は以下の通り（EDL = BSD-3-Clause）:

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

EPL-2.0 の全文は https://www.eclipse.org/legal/epl-2.0/ を参照してください。
（Maven Central から取得した jar を依存として利用しており、ソースの再配布は行っていません）

## その他の依存

- Jetpack Compose / Material3 / AndroidX（Apache License 2.0, Copyright The Android Open Source Project）
- Kotlin Standard Library（Apache License 2.0）
