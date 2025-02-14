load("@rules_java//java:defs.bzl", orginal_java_binary = "java_binary", orginal_java_library = "java_library")
load("//project/flags:report_unused.bzl", "REPORT_UNUSED")
load("//:tools/bazel/unused_dependencies.bzl", "report_unused")
load("//:tools/bazel/aeriform.bzl", "aeriformAnnotations")

def java_library(**kwargs):
    tags = kwargs.pop("tags", [])

    orginal_java_library(tags = tags + ["harness"], **kwargs)

    if REPORT_UNUSED:
        report_unused(orginal_java_library, tags = tags, **kwargs)

    #aeriformAnnotations(**kwargs)

def harness_sign(jar):
    name = jar.rsplit("/", 1)[-1][:-4] + "_signed"
    signed_jar = jar.rsplit(":", 1)[-1][:-4] + "_signed.jar"

    native.genrule(
        name = name,
        srcs = [jar],
        outs = [signed_jar],
        exec_tools = ["//:tools/bazel/signer.sh"],
        cmd = " && ".join([
            "cp $(location %s) \"$@\"" % (jar),
            " ".join([
                "$(location //:tools/bazel/signer.sh)",
                "\"bazel-out/stable-status.txt\"",
                "$(JAVABASE)/bin/jarsigner",
                "-storetype pkcs12",
                "-keystore \\$${SIGNER_KEY_STORE}",
                "-storepass \\$${SIGNER_KEY_STORE_PASSWORD}",
                "\"$@\"",
                "harnessj",
            ]),
        ]),
        visibility = ["//visibility:public"],
        toolchains = ["@bazel_tools//tools/jdk:current_host_java_runtime"],
        stamp = True,
    )

    return name

def java_binary(**kwargs):
    name = kwargs.get("name")

    sign = kwargs.pop("sign", False)

    tags = kwargs.pop("tags", [])
    orginal_java_binary(tags = tags + ["harness"], **kwargs)

    if sign:
        harness_sign(name + "_deploy.jar")

    if REPORT_UNUSED:
        report_unused(orginal_java_binary, **kwargs)
